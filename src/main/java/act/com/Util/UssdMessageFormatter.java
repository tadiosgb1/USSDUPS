package act.com.Util;

import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;

public class UssdMessageFormatter {

    private static final int ASCII_LIMIT = 170;
    private static final int UNICODE_LIMIT = 80;

    private static final Map<String, String> ORG_NAMES = new HashMap<>();

    static {
        ORG_NAMES.put("TSEGEREDA WATER UTILITY", "Tsigereda Water");
        ORG_NAMES.put("MAJI TOWN WATER SUPPLY", "Maji Water");
        ORG_NAMES.put("ADDIS ABABA WATER AND SEWERAGE AUTHORITY", "AAWSA");

    }
    public static boolean containsAmharic(String text) {

        if (text == null)
            return false;

        for (char c : text.toCharArray()) {

            Character.UnicodeBlock block =
                    Character.UnicodeBlock.of(c);

            if (block == Character.UnicodeBlock.ETHIOPIC
                    || block == Character.UnicodeBlock.ETHIOPIC_SUPPLEMENT
                    || block == Character.UnicodeBlock.ETHIOPIC_EXTENDED
                    || block == Character.UnicodeBlock.ETHIOPIC_EXTENDED_A) {

                return true;
            }
        }
        return false;
    }

    private static final String[] TITLES = {
            "አቶ", "ወ/ሮ", "ወ/ሪት", "ወይዘሮ", "ወይዘሪት", "ዶ/ር", "ፕሮፌሰር",
            "Mr.", "Mrs.", "Ms.", "Miss", "Dr.", "Professor"
    };

    public static String cleanCustomerName(String fullName) {

        if (fullName == null)
            return "";

        fullName = fullName.trim();

        for (String title : TITLES) {
            if (fullName.startsWith(title + " ")) {
                fullName = fullName.substring(title.length()).trim();
                break;
            }
        }
        String[] names = fullName.split("\\s+");
        return names.length == 0 ? fullName : names[0];
    }

    public static String shortOrganizationName(String orgName) {
        if (orgName == null)
            return "";

        String value = ORG_NAMES.get(orgName.toUpperCase());

        if (value != null)
            return value;

        orgName = orgName.replace("WATER UTILITY", "");
        orgName = orgName.replace("WATER SUPPLY", "");
        orgName = orgName.replace("AUTHORITY", "");
        orgName = orgName.replace("WATER SUPPLY AND SEWERAGE", "");
        orgName = orgName.replace("WATER SUPPLY AND SEWERAGE SERVICE", "");
        orgName = orgName.replace("ORGANIZATION", "");
        orgName = orgName.replace("WATER SERVICE", "");
        orgName = orgName.replace("OFFICE", "");
        orgName = orgName.replace("ዉሃና ፍሳሽ ኣገልግሎት ጽ/ቤት", "");
        orgName = orgName.replace("TOWN", "");

        orgName = orgName.trim();

        if (orgName.length() > 20)
            return orgName.substring(0, 20);

        return orgName;

    }

    public static String formatAmount(double amount) {

        DecimalFormat df = new DecimalFormat("#.##");

        return df.format(amount);

    }

    public static String shortMonth(String month) {

        if (month == null)
            return "";

        month = month.replace("January", "Jan");
        month = month.replace("February", "Feb");
        month = month.replace("March", "Mar");
        month = month.replace("April", "Apr");
        month = month.replace("August", "Aug");
        month = month.replace("September", "Sep");
        month = month.replace("October", "Oct");
        month = month.replace("November", "Nov");
        month = month.replace("December", "Dec");

        return month
                .replace(" 20", "-");

    }

    public static String compressMessage(String message) {

        if (message == null)
            return "";

        message = message.replaceAll("[ ]{2,}", " ");
        message = message.replaceAll("\n{2,}", "\n");

        return message.trim();
    }

    public static String fitUSSD(String message) {

        message = compressMessage(message);

        int limit = containsAmharic(message)
                ? UNICODE_LIMIT
                : ASCII_LIMIT;

        if (message.length() <= limit)
            return message;

        int lastSpace = message.lastIndexOf(' ', limit - 4);

        if (lastSpace > 0) {
            return message.substring(0, lastSpace) + "...";
        }

        return message.substring(0, limit - 3) + "...";
    }

    public static String buildSingleBillMessage(
            String month,
            String customer,
            double amount,
            String organization) {

        customer = cleanCustomerName(customer);
        month = shortMonth(month);
        organization =
                shortOrganizationName(organization);

        String english =
                "Unpaid " +
                        month +
                        "\n" +
                        customer +
                        "\n" +
                        formatAmount(amount) +
                        " ETB\n" +
                        organization +
                        "\n1.Pay";

        return fitUSSD(english);

    }

    public static String buildConfirmationMessage(
            String month,
            String customer,
            double amount,
            String organization) {

        customer = cleanCustomerName(customer);
        month = shortMonth(month);

        organization =
                shortOrganizationName(organization);

        String msg =
                month +
                        "\n" +
                        customer +
                        "\n" +
                        formatAmount(amount) +
                        " ETB\n" +
                        organization +
                        "\n1.Confirm";

        return fitUSSD(msg);

    }

    public static String buildRangeConfirmationMessage(
            String fromMonth,
            String toMonth,
            String customer,
            double amount,
            String organization) {

        customer = cleanCustomerName(customer);
        organization = shortOrganizationName(organization);
        fromMonth = shortMonth(fromMonth);
        toMonth = shortMonth(toMonth);

        String msg =
                fromMonth + "-" + toMonth +
                        "\n" +
                        customer +
                        "\n" +
                        formatAmount(amount) +
                        " ETB" +
                        "\n" +
                        organization +
                        "\n1.Confirm";

        return fitUSSD(msg);
    }

    public static String buildAllConfirmationMessage(
            String fromMonth,
            String toMonth,
            String customer,
            double amount,
            String organization) {

        customer = cleanCustomerName(customer);
        organization = shortOrganizationName(organization);

        String msg =
                "All Bills" +
                        "\n" +
                        fromMonth + " - " + toMonth +
                        "\n" +
                        customer +
                        "\n" +
                        formatAmount(amount) + " ETB" +
                        "\n" +
                        organization +
                        "\n1.Confirm";

        return fitUSSD(msg);
    }

    public static String buildSuccessMessage(
            String accountHolder,
            String customer,
            String organization,
            String paymentPeriod,
            double amount,
            String reference,
            String paymentCode) {

        accountHolder = cleanCustomerName(accountHolder);
        customer = cleanCustomerName(customer);
        organization = shortOrganizationName(organization);

        StringBuilder message = new StringBuilder();

        message.append("Paid");

        if (paymentPeriod != null && !paymentPeriod.isEmpty()) {
            message.append("\n").append(paymentPeriod);
        }

        message.append("\n").append(customer);

        if (!accountHolder.equalsIgnoreCase(customer)) {
            message.append("\nFrom: ").append(accountHolder);
        }

        message.append("\n")
                .append(formatAmount(amount))
                .append(" ETB");

        if (organization != null && !organization.isEmpty()) {
            message.append("\n").append(organization);
        }

        if (reference != null && !reference.isEmpty()) {
            message.append("\nRef:").append(reference);
        }

        if (paymentCode != null && !paymentCode.isEmpty()) {
            message.append("\nCode:").append(paymentCode);
        }

        return fitUSSD(message.toString());
    }

    public static int getMaxUssdLength(String text) {
        return containsAmharic(text) ? 80 : 180;
    }

}