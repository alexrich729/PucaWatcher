import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.io.FileNotFoundException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class PucaTradeTracker {
    private static String userName = System.getenv("PUCA_USERNAME");    //environment variables
    private static String password = System.getenv("PUCA_PASSWORD");    //environment variables
    public File file;           //for storing card deals from the previous data extraction
    public FileWriter fw;
    public Scanner scan;
    public WebDriver driver;    //to use PucaTrade

    public PucaTradeTracker() {
        // Init chromedriver
        String chromeDriverPath = "/Users/alexrich/Downloads/chromedriver";
        System.setProperty("webdriver.chrome.driver", chromeDriverPath);
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless", "--disable-gpu", "--window-size=1920,1200","--ignore-certificate-errors");
        driver = new ChromeDriver(options);
    }

    public static void main(String[] args) throws IOException, InterruptedException, IllegalStateException {
        PucaTradeTracker ptt = new PucaTradeTracker();
        ptt.run();
    }

    public void run() throws IOException, InterruptedException, IllegalStateException {
        if (this.userName == null)
            throw new IllegalStateException("Missing environment variable for PUCA_USERNAME");
        if (this.password == null)
            throw new IllegalStateException("Missing environment variable for PUCA_PASSWORD");
        while (true) {
            if (!logIn())
                System.exit(1);

            Thread.sleep(3000);

            setUpPage();

            //create new file if it doesn't exist
            try {
                file = new File("PucaDatabase.txt");
                if (file.createNewFile()) {
                    System.out.println("File created: " + file.getName());
                } else {
                    System.out.println("File already exists.");
                }

                fw = new FileWriter("PucaDatabase.txt");
                scan = new Scanner(file);

                //each array loc corresponds to a file line, telling whether it should be kept when file is updated
                ArrayList<Boolean> keepLine = new ArrayList<Boolean>();

                //sets all existing data to not keep
                while (scan.hasNextLine()) {
                    scan.nextLine();
                    keepLine.add(false);
                }
                scan.close();
                scan = new Scanner(file);

                findDeals(keepLine);

                //remove old lines by making copy file
                File copy = new File("copy.txt");
                fw = new FileWriter("copy.txt");

                int index = 0;
                while (scan.hasNextLine()) {
                    String data = scan.nextLine();
                    if (keepLine.get(index)) {
                        fw.write(data + "\n");
                    }
                    index++;
                }
                copy.renameTo(file);

                fw.close();
                scan.close();

                /**
                 // Take a screenshot of the current page
                 Thread.sleep(2000);
                 File screenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
                 FileUtils.copyFile(screenshot, new File("screenshot1.png"));
                 */


                driver.quit();

                sendEmail();

            } catch (IOException e) {
                System.out.println("An error occurred.");
                e.printStackTrace();
            }

            //sleep for 6 hours before program checks again
            Thread.sleep(21600000);
        }
    }

    /**
     * Logins into my PucaTrade account on the chromedriver
     * @return  true if login is successful, false otherwise
     */
    public boolean logIn() {
        // Get the login page
        driver.get("https://pucatrade.com/login");

        // Search for username / password input and fill the inputs
        driver.findElement(By.xpath("//input[@name='login']")).sendKeys(userName);
        driver.findElement(By.xpath("//input[@type='password']")).sendKeys(password);

        // Locate the login button and click on it
        driver.findElement(By.xpath("//button[@type='submit']")).click();

        if(driver.getCurrentUrl().equals("https://pucatrade.com/login")) {
            System.out.println("Incorrect credentials");
            driver.quit();
            return false;
        }
        System.out.println("Successfuly logged in");
        return true;
    }

    /**
     * Sets up page to point where cards with good deals can be read
     * @throws InterruptedException
     */
    public void setUpPage() throws InterruptedException {
        //navigates to necessary page from PucaTrade homepage
        driver.findElement(By.xpath("//a[text()='SEND CARDS']")).click();

        Thread.sleep(3000);

        //makes sure this button is selected so that recommended cards are shown
        WebElement element = driver.findElement(By.xpath("//div[contains(concat(' ',normalize-space(@class),' '),'btn-circle')]"));
        if (element.getAttribute("class").equals("button btn-circle  active")) {
            element.click();
        }

        String target = "filter-item toggle-button toggle-dropdown has-icon sorting sort desc";
        String target2 = "filter-item toggle-button toggle-dropdown has-icon sorting sort asc";
        element = driver.findElement(By.xpath("//div[contains(concat(' ',normalize-space(@class),' '),'filter-item toggle-button toggle-dropdown has-icon sorting sort ')]"));
        if (element.getAttribute("class").equals(target) ||
                element.getAttribute("class").equals(target2)) {
            WebElement innerElement;
            if (element.getAttribute("class").equals(target2) ||
                    element.getAttribute("innerHTML").indexOf("Bonus %") == -1) {
                element.click();
                innerElement = element.findElement(By.xpath("//div[contains(concat(' ',normalize-space(@class),' '),'dropdown')]"));
                innerElement.findElement(By.xpath("//label[text()='Bonus %']")).click();
            }
        }
    }

    /**
     * Goes through list of deals fond on PucaTrade page and checks to see if they already exist or have a better deal in
     * the file, in which case it isn't added. Also keeps track of which lines in the file to keep for later.
     * @param keepLine                  bool array for which lines of file to keep
     * @throws FileNotFoundException
     * @throws IOException
     */
    public void findDeals(ArrayList<Boolean> keepLine) throws FileNotFoundException, IOException {
        List<WebElement> offers = driver.findElements(By.xpath("//div[contains(concat(' ',normalize-space(@class),' '),'item clear animated')]"));
        String message = "";
        int index = 0;
        String name;
        String pucaPoints;
        String bonus;
        while (index < offers.size()) {
            WebElement element = offers.get(index);
            name = element.findElement(By.xpath(".//a[contains(concat(' ',normalize-space(@class),' '),'autocard promoted')]")).getText();

            pucaPoints = element.findElement(By.xpath(".//div[contains(concat(' ',normalize-space(@class),' '),'price small')]")).getText();

            bonus = element.findElement(By.xpath(".//div[contains(concat(' ',normalize-space(@class),' '),'up')]")).getText();

            if (Integer.parseInt(bonus) < 100)
                break;

            if (Integer.parseInt(pucaPoints.replace(",", "")) >= 2000) {
                //check to see if offer was already recorded
                boolean alreadyExists = false;
                int tracker = 0;
                while (scan.hasNextLine() && !alreadyExists) {
                    String data = scan.nextLine();
                    String dataName = data.substring(0, data.indexOf('-') - 1);
                    String dataBonus = data.substring(data.lastIndexOf('-') + 2);
                    //doesn't keep card if it already exists and exits for loop without keeping it if less than a previous value for same card
                    if (data.equals(name + " - " + pucaPoints + " - " + bonus)) {
                        System.out.println("happens");
                        keepLine.set(tracker, false);
                        alreadyExists = true;
                    } else if (name.equals(dataName) && Integer.parseInt(bonus) < Integer.parseInt(dataBonus)) {
                        alreadyExists = true;
                    }
                    tracker++;
                }
                scan.close();
                scan = new Scanner(file);
                if (!alreadyExists && !message.contains(name)) {
                    message += name + " - " + pucaPoints + " - " + bonus + "\n";
                    keepLine.add(true);
                }
            }
            index++;
        }
        fw.write(message);
        fw.close();
    }

    /**
     * Sends me a text from my email with info on card deals
     * @throws FileNotFoundException
     */
    public void sendEmail() throws FileNotFoundException, IllegalStateException {
        // Reads file into one string for writing in email
        scan = new Scanner(file);
        String messageText = "";
        ArrayList<String> messages = new ArrayList<>();
        int count = 0;
        while (scan.hasNextLine()) {
            count++;
            messageText += scan.nextLine();
            if (count == 3) {
                messages.add(messageText);
                count = 0;
                messageText = "";
            } else if (scan.hasNextLine())
                messageText += "\n";
            else if (messageText.length() > 0)
                messages.add(messageText);
        }
        scan.close();

        // Gmail username from environment
        final String username = System.getenv("GMAIL_USERNAME");

        // Gmail password from environment
        final String password = System.getenv("GMAIL_PASSWORD");

        // Receiver's email ID (phone number to send to my phone) from environment
        String receiver = System.getenv("SMS_EMAIL");

        if (username == null)
            throw new IllegalStateException("Missing environment variable for GMAIL_USERNAME");
        if (password == null)
            throw new IllegalStateException("Missing environment variable for GMAIL_PASSWORD");
        if (receiver == null)
            throw new IllegalStateException("Missing environment variable for SMS_EMAIL");

        // Sender's email ID (same as gmail username)
        String sender = username;

        // Sending email from gmail
        String host = "smtp.gmail.com";

        // Port of SMTP
        String port = "587";

        Properties properties = new Properties();

        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.starttls.enable", "true");
        properties.put("mail.smtp.host", host);
        properties.put("mail.smtp.port", port);

        // Create session object passing properties and authenticator instance
        Session session = Session.getInstance(properties, new javax.mail.Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        });

        try {
            for (count = 0; count < messages.size(); count++) {
                // Create MimeMessage object
                MimeMessage message = new MimeMessage(session);

                // Set the Senders mail to From
                message.setFrom(new InternetAddress(sender));

                // Set the recipients email address
                message.addRecipient(Message.RecipientType.TO, new InternetAddress(receiver));

                // get current time
                DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
                LocalDateTime now = LocalDateTime.now();

                // Subject of the email
                if (count == 0)
                    message.setSubject("Best deals - " + dtf.format(now));
                else
                    message.setSubject("");

                // Body of the email
                message.setText(messages.get(count));

                // Send email.
                if (messages.get(count).length() > 0)
                    Transport.send(message);
            }
            System.out.println("Mail sent successfully");
        } catch (MessagingException me) {
            me.printStackTrace();
        }
    }
}
