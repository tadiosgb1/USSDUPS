package act.com.service;

import act.com.Util.SSLContext;
import act.com.dao.CustomerInfoViewDao;
import act.com.dao.WaterOrganizationMappingDao;
import act.com.dao.DerashOrganizationMappingDao; // NEW - see DerashOrganizationMappingDao.java
import act.com.dto.RequestResponseCol;
import act.com.models.*;

import javax.ejb.EJB;
import javax.ejb.Stateful;
import javax.enterprise.context.RequestScoped;
import javax.json.*;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import javax.ws.rs.*;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.StreamingOutput;

import act.com.session.SessionManager;
import org.json.JSONObject;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;


import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

import act.com.Util.UssdMessageFormatter;
import act.com.dao.PaymentCodeValidationLogDao;


@Path("/ups")
@RequestScoped
@Stateful
public class ups {

    private static final Properties config = new Properties();
    private static final Client client;
    private static final Logger LOGGER = Logger.getLogger(ups.class.getName());

    private static final Pattern DERASH_PATTERN = Pattern.compile("^\\d{3}-[Cc]-\\d{5}$");
    private static final Pattern GEEZ_PATTERN = Pattern.compile("^[2-9]\\d{9}$");

    private enum WaterProvider {
        DERASH, GEEZ, UPS
    }

    static {
        try (InputStream inputStream = ups.class.getClassLoader().getResourceAsStream("config.properties")) {
            if (inputStream == null)
                throw new RuntimeException("Failed to load Config properties");
            config.load(inputStream);
            client = ClientBuilder.newBuilder()
                    .sslContext(SSLContext.newContext())
                    .build();

            System.out.println("CONFIG LOADED SUCCESSFULLY");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @PersistenceContext(unitName = "primary")
    private EntityManager em;

    @EJB
    CustomerInfoViewDao customerInfoViewDao;

    @EJB
    private WaterOrganizationMappingDao waterOrganizationMappingDao; // used for UPS lookups

    @EJB
    private DerashOrganizationMappingDao derashOrganizationMappingDao; // used for Derash lookups

    @EJB
    private PaymentCodeValidationLogDao paymentCodeValidationLogDao;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public javax.ws.rs.core.Response call(final RequestResponseCol reqres) {
        List<CustomerInfoView> customerAccounts = this.customerInfoViewDao.getAccounts(reqres.getMsisdn());
        javax.ws.rs.core.Response.ResponseBuilder builder = null;
        RequestResponseCol sessionState = SessionManager.getSession(reqres.getSessionId());
        if (sessionState != null) {
            reqres.setUnpaidBills(sessionState.getUnpaidBills());
            reqres.setBillType(sessionState.getBillType());
            reqres.setOrganizationCode(sessionState.getOrganizationCode());
            reqres.setPaymentCode(sessionState.getPaymentCode());
            reqres.setSelectedPaymentMonthSize(sessionState.getSelectedPaymentMonthSize());
            reqres.setSingle(sessionState.isSingle());
            reqres.setTotalAmount(sessionState.getTotalAmount());
            reqres.setCurrentPage(sessionState.getCurrentPage());
            reqres.setRequiresOrganizationCode(sessionState.isRequiresOrganizationCode());
            reqres.setPaymentProvider(sessionState.getPaymentProvider());
        }

        LOGGER.info("------------------ Incoming USSD request for " + reqres.getMsisdn() + " with iteration: " + reqres.getCurrentItteration());
        try {
            act.com.models.Response res = new act.com.models.Response();
            if (customerAccounts.isEmpty()) {
                String message = this.getLanguageString(reqres.getLanguage(), "no_acc");
                res.setAction("end");
                res.setTransactionId(reqres.getSessionId());
                builder = buildResponse(reqres, message, res);
            }
            int navigationCount = 0;
            int calculatedIteration = reqres.getCurrentItteration();
            for (int i = 0; i < reqres.getRequestList().size(); i++) {
                if (reqres.getRequestList().get(i).getUssdrequestString().equals("*") || reqres.getRequestList().get(i).getUssdrequestString().equals("#")) {
                    navigationCount++;
                }
            }
            if ("*".equals(reqres.getCurrentRequest()) || "#".equals(reqres.getCurrentRequest())) {
                navigationCount++;
            }
            if (navigationCount > 0) {
                calculatedIteration = reqres.getCurrentItteration() - navigationCount;
                LOGGER.info("############################ CALCULATED ITERATION: " + calculatedIteration);
            }

            if (reqres.getCurrentItteration() == 2) {
                if ("2".equalsIgnoreCase(reqres.getCurrentRequest())) {
                    reqres.setBillType("Education");
                } else {
                    reqres.setBillType("Watersupply");
                }
                SessionManager.storeSession(reqres.getSessionId(), reqres);
                builder = returnAccountList(reqres, customerAccounts, builder);
            } else if (calculatedIteration == 3) {
                builder = handleSelectedAccount(reqres, customerAccounts, res);
            } else if (calculatedIteration == 4) {
                builder = handlePaymentCode(reqres, res);
            } else if (calculatedIteration == 5) {
                builder = handleUnpaidBills(reqres, res);
            } else if (calculatedIteration == 6) {
                if (reqres.isSingle())
                    builder = processPaymentSelection(reqres, res);
                else
                    builder = displayUnpaidPayments(reqres, res);
            } else if (calculatedIteration == 7) {
                if (reqres.isSingle())
                    builder = paymentCompletion(reqres, res, customerAccounts);
                else
                    builder = processPaymentSelection(reqres, res);
            } else if (calculatedIteration == 8) {
                builder = paymentCompletion(reqres, res, customerAccounts);
            }
            return builder.build();
        } catch (Exception e) {
            act.com.models.Response res = new act.com.models.Response();
            res.setAction("end");
            LOGGER.log(Level.SEVERE, "Exception while processing:", e);
            SessionManager.removeSession(reqres.getSessionId());
            builder = buildResponse(reqres, "Connection lost. Please try again.", res);
        }
        return builder.build();
    }

    private javax.ws.rs.core.Response.ResponseBuilder returnAccountList(RequestResponseCol reqres,
                                                                        List<CustomerInfoView> customerAccounts, javax.ws.rs.core.Response.ResponseBuilder builder)
            throws Exception {
        String message = this.getLanguageString(reqres.getLanguage(), "sacc");
        String accounts = "";
        int optionNumber = 1;
        for (CustomerInfoView account : customerAccounts) {
            accounts = accounts + "\n" + optionNumber + ". " + account.getAccount();
            optionNumber++;
        }

        message = message.replace("@account", accounts);

        act.com.models.Response res = new act.com.models.Response();
        res.setAction("request");
        res.setTransactionId(reqres.getSessionId());
        return buildResponse(reqres, message, res);
    }

    private Response.ResponseBuilder handleSelectedAccount(
            RequestResponseCol reqres, List<CustomerInfoView> customerAccounts, act.com.models.Response res) {

        try {
            LOGGER.info("---------------- HANDLE SELECTED ACCOUNT -------------");

            int selection = Integer.parseInt(reqres.getCurrentRequest());

            if (selection <= 0 || selection > customerAccounts.size()) {
                SessionManager.removeSession(reqres.getSessionId());
                res.setAction("end");
                return buildResponse(reqres, "Invalid account selection", res);
            }

            res.setAction("request");
            return buildResponse(reqres, "Enter Payment Code", res);
        } catch (Exception e) {

            LOGGER.log(Level.SEVERE, "Exception while selecting account", e);
            SessionManager.removeSession(reqres.getSessionId());
            res.setAction("end");
            return buildResponse(reqres, "Invalid input. Please try again", res);
        }
    }

    private WaterProvider classifyPaymentCode(String rawCode) {
        if (rawCode == null) {
            return WaterProvider.UPS;
        }
        String trimmed = rawCode.trim();
        if (trimmed.length() == 11 && DERASH_PATTERN.matcher(trimmed).matches()) {
            return WaterProvider.DERASH;
        }
        if (trimmed.length() == 10 && GEEZ_PATTERN.matcher(trimmed).matches()) {
            return WaterProvider.GEEZ;
        }
        return WaterProvider.UPS;
    }

    private javax.ws.rs.core.Response.ResponseBuilder handlePaymentCode(RequestResponseCol reqres,
                                                                        act.com.models.Response res) {
        try {
            LOGGER.info("------------------- HANDLING PAYMENT CODE ---------------");
            String rawCode = reqres.getCurrentRequest().trim();
            LOGGER.info("------------ ENTERED PAYMENT CODE: " + rawCode);

            if( rawCode.isEmpty()){
                SessionManager.removeSession(reqres.getSessionId());
                res.setAction("end");
                return buildResponse(reqres, "Invalid Payment Code", res);
            }

            if ("Education".equalsIgnoreCase(reqres.getBillType())) {
                reqres.setPaymentProvider(WaterProvider.UPS.name());
                reqres.setOrganizationCode(null);
                reqres.setPaymentCode(rawCode);
                SessionManager.storeSession(reqres.getSessionId(), reqres);
                return fetchBills(reqres, res);
            }

            WaterProvider provider = classifyPaymentCode(rawCode);
            reqres.setPaymentProvider(provider.name());
            LOGGER.info("------------ CLASSIFIED AS: " + provider);

            switch (provider) {
                case DERASH: {
                    String prefix = rawCode.substring(0, 3);
                    DerashOrganizationMapping mapping = derashOrganizationMappingDao.findByCode(prefix);
                    if (mapping == null) {
                        reqres.setPaymentCode(rawCode);
                        logPaymentCodeValidation(reqres, safeProvider(reqres.getPaymentProvider()), false, "Organization not found for the provided payment code.");
                        SessionManager.removeSession(reqres.getSessionId());
                        res.setAction("end");
                        return buildResponse(reqres, "Organization not found for the provided payment code.", res);
                    }

                    reqres.setOrganizationCode(mapping.getOrganizationCode());
                    reqres.setPaymentCode(rawCode.substring(0,4) + rawCode.substring(4, 5).toLowerCase()
                            + rawCode.substring(5));
                    break;
                }
                case UPS: {
                    if (rawCode.length() < 3) {
                        reqres.setPaymentCode(rawCode);
                        logPaymentCodeValidation(reqres, safeProvider(reqres.getPaymentProvider()), false, "Invalid payment code");
                        SessionManager.removeSession(reqres.getSessionId());
                        res.setAction("end");
                        return buildResponse(reqres, "Invalid Payment Code", res);
                    }
                    String prefix = rawCode.substring(0, 3);
                    WaterOrganizationMapping mapping = waterOrganizationMappingDao.findByWegCode(prefix);
                    if (mapping == null) {
                        reqres.setPaymentCode(rawCode);
                        logPaymentCodeValidation(reqres, safeProvider(reqres.getPaymentProvider()), false, "Organization not found for the provided payment code.");
                        SessionManager.removeSession(reqres.getSessionId());
                        res.setAction("end");
                        return buildResponse(reqres, "Organization not found for the provided payment code.", res);
                    }
                    reqres.setOrganizationCode(mapping.getOrganizationCode());
                    reqres.setPaymentCode(rawCode.substring(3));
                    break;
                }
                case GEEZ:
                default: {
                    reqres.setOrganizationCode(null);
                    reqres.setPaymentCode(rawCode);
                    break;
                }
            }

            SessionManager.storeSession(reqres.getSessionId(), reqres);

            if (provider == WaterProvider.GEEZ) {
                return fetchGeezBills(reqres, res);
            }
            return fetchBills(reqres, res);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "PAYMENT CODE ERROR", e);
            SessionManager.removeSession(reqres.getSessionId());
            res.setAction("end");
            return buildResponse(reqres, "Invalid Payment Code", res);
        }
    }

    private Response.ResponseBuilder fetchBills(RequestResponseCol reqres, act.com.models.Response res) {

        LOGGER.info("------------------------ FETCHING BILLS FROM API (Derash/UPS) ------------------");

        try {
            JsonObject requestObj = buildBillInquiryRequest(reqres);
            LOGGER.info("--------------- BILL INQUIRY REQUEST: " + requestObj.toString());
            String endpoint = config.getProperty("ups.getBill");

            String accessToken = getAccessToken();

            Response apiResponse = client
                    .target(endpoint)
                    .request(MediaType.APPLICATION_JSON)
                    .header("Authorization",
                            "Bearer " + accessToken)
                    .post(Entity.json(requestObj));

            String billsResponseString = apiResponse.readEntity(String.class);
            int responseStatus = apiResponse.getStatus();

            LOGGER.info("---------------- BILL API RESPONSE " + billsResponseString);
            LOGGER.info("---------------- BILL API STATUS: " + responseStatus);

            JsonReader jsonReader = Json.createReader(new StringReader(billsResponseString));
            JsonObject jsonObject = jsonReader.readObject();

            if (responseStatus == 200) {
                boolean success = jsonObject.getBoolean("success");
                if (success) {
                    JsonArray responseArr = jsonObject.getJsonArray("data");
                    JsonObject firstObj = responseArr.getJsonObject(0);
                    if (responseArr.isEmpty()) {
                        logPaymentCodeValidation(reqres, safeProvider(reqres.getPaymentProvider()), false, "No unpaid months found for the given payment code.");
                        SessionManager.removeSession(reqres.getSessionId());
                        res.setAction("end");
                        return buildResponse(reqres, "No unpaid months found for the given payment code.", res);
                    }
                    reqres.setUnpaidBills(billsResponseString);
                    SessionManager.storeSession(reqres.getSessionId(), reqres);

                    logPaymentCodeValidation(reqres, safeProvider(reqres.getPaymentProvider()), true,
                            "Payment code valid - " + firstObj.getString("full_name", ""));

                    res.setAction("request");
                    return buildResponse(reqres,
                            "Payment details for " + firstObj.getString("full_name") +
                                    " (" + UssdMessageFormatter.shortOrganizationName(firstObj.getString("orgName")) + ").\nEnter 1 to continue.",
                            res);
                } else {
                    String failMessage = jsonObject.getString("message", "Something went wrong, please try again");
                    logPaymentCodeValidation(reqres, safeProvider(reqres.getPaymentProvider()), false, failMessage);
                    SessionManager.removeSession(reqres.getSessionId());
                    res.setAction("end");
                    return buildResponse(reqres, jsonObject.getString("message", "Something went wrong, please try again"), res);
                }
            } else {
                String message = jsonObject.getString("message", "Something went wrong, please try again");
                jsonReader.close();
                LOGGER.log(Level.SEVERE, "ERROR: ", message);
                logPaymentCodeValidation(reqres, safeProvider(reqres.getPaymentProvider()), false, message);
                SessionManager.removeSession(reqres.getSessionId());
                res.setAction("end");
                return buildResponse(reqres, message, res);
            }

        } catch (Exception e) {

            LOGGER.log(Level.SEVERE, "FETCH BILL ERROR", e);
            logPaymentCodeValidation(reqres, safeProvider(reqres.getPaymentProvider()), false, "Bill inquiry error: " + e.getMessage());
            SessionManager.removeSession(reqres.getSessionId());
            res.setAction("end");
            return buildResponse(reqres, "Unable to fetch bills", res);
        }
    }

    private Response.ResponseBuilder fetchGeezBills(RequestResponseCol reqres, act.com.models.Response res) {

        LOGGER.info("------------------------ FETCHING BILLS FROM API (Geez) ------------------");

        try {
            String baseUrl = config.getProperty("geez.getBill");
            String endpoint = baseUrl.endsWith("/") ? baseUrl + reqres.getPaymentCode() : baseUrl + "/" + reqres.getPaymentCode();

            String accessToken = getAccessToken();

            Response apiResponse = client
                    .target(endpoint)
                    .request(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .get();

            String rawResponseString = apiResponse.readEntity(String.class);
            int responseStatus = apiResponse.getStatus();

            LOGGER.info("---------------- GEEZ BILL API RESPONSE ----------------");
            LOGGER.info(rawResponseString);
            LOGGER.info("---------------- END GEEZ BILL API RESPONSE ------------");
            LOGGER.info("---------------- GEEZ BILL API STATUS: " + responseStatus);

            if (responseStatus != 200) {
                logPaymentCodeValidation(reqres, WaterProvider.GEEZ, false, "Unable to fetch bills (HTTP " + responseStatus + ")");
                SessionManager.removeSession(reqres.getSessionId());
                res.setAction("end");
                return buildResponse(reqres, "Unable to fetch bills", res);
            }

            LOGGER.info("******************* BEFORE NORMALIZE "+rawResponseString);

            String normalized = normalizeGeezBills(rawResponseString);

            LOGGER.info("=================================================");
            LOGGER.info("NORMALIZED GEEZ RESPONSE");
            LOGGER.info(normalized);
            LOGGER.info("=================================================");

            try (JsonReader normalizedReader =
                         Json.createReader(new StringReader(normalized))) {

                JsonObject normalizedJson = normalizedReader.readObject();

                LOGGER.info("Normalized success = " +
                        normalizedJson.getBoolean("success", false));

                JsonArray data =
                        normalizedJson.getJsonArray("data");

                LOGGER.info("Normalized bill count = " +
                        (data == null ? 0 : data.size()));

                if (data != null && !data.isEmpty()) {
                    LOGGER.info("First normalized bill = " +
                            data.getJsonObject(0));
                }
            }
            JsonReader jsonReader = Json.createReader(new StringReader(normalized));
            JsonObject jsonObject = jsonReader.readObject();

            if (!jsonObject.getBoolean("success", false)) {
                String failMessage = jsonObject.getString("message", "No unpaid months found for the given payment code.");
                logPaymentCodeValidation(reqres, WaterProvider.GEEZ, false, failMessage);
                SessionManager.removeSession(reqres.getSessionId());
                res.setAction("end");
                return buildResponse(reqres, jsonObject.getString("message", "No unpaid months found for the given payment code."), res);
            }

            JsonArray responseArr = jsonObject.getJsonArray("data");
            if (responseArr == null || responseArr.isEmpty()) {
                logPaymentCodeValidation(reqres, WaterProvider.GEEZ, false, "No unpaid months found for the given payment code.");
                SessionManager.removeSession(reqres.getSessionId());
                res.setAction("end");
                return buildResponse(reqres, "No unpaid months found for the given payment code.", res);
            }

            JsonObject firstObj = responseArr.getJsonObject(0);
            reqres.setUnpaidBills(normalized);
            SessionManager.storeSession(reqres.getSessionId(), reqres);

            logPaymentCodeValidation(reqres, WaterProvider.GEEZ, true,
                    "Payment code valid - " + firstObj.getString("full_name", ""));

            res.setAction("request");
            return buildResponse(reqres,
                    "Payment details for " + firstObj.getString("full_name") +
                            " (" + firstObj.getString("orgName") + ").\nEnter 1 to continue.",
                    res);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "FETCH GEEZ BILL ERROR", e);
            logPaymentCodeValidation(reqres, WaterProvider.GEEZ, false, "Bill inquiry error: " + e.getMessage());
            SessionManager.removeSession(reqres.getSessionId());
            res.setAction("end");
            return buildResponse(reqres, "Unable to fetch bills", res);
        }
    }

    private String normalizeGeezBills(String rawGeezResponse) {
        JsonReader reader = Json.createReader(new StringReader(rawGeezResponse));
        JsonObject raw = reader.readObject();

        boolean success = "0".equals(getJsonValueAsString(raw, "status"));
        System.out.println("************************************ GEEZ *****************************************");
        System.out.println("Success: "+success);

        if (!success) {
            String message = raw.getString("description", "No unpaid months found for the given payment code.");
            String additionalInfo = raw.getString("additionalInfo", "");
            if (!additionalInfo.isEmpty()) {
                message = message + " (" + additionalInfo + ")";
            }
            return Json.createObjectBuilder()
                    .add("success", false)
                    .add("message", message)
                    .build()
                    .toString();
        }

        JsonObject customerInfo = raw.getJsonObject("customerInfo");
        String accountId = getJsonValueAsString(customerInfo, "accountId");
        String customerName = customerInfo.getString("customerName", "");
        String ketema = customerInfo.getString("ketema", "");
        String bankAccount = customerInfo.getString("bankAccount", "");
        String orgLabel = ketema.isEmpty() ? "Geez Water Supply" : ketema;

        JsonArray paymentInfo = raw.getJsonArray("paymentInfo");
        JsonArrayBuilder normalizedArr = Json.createArrayBuilder();

        for (JsonValue v : paymentInfo) {
            JsonObject src = (JsonObject) v;
            String billId = getJsonValueAsString(src, "billId");
            String total = getJsonValueAsString(src, "total");
            String billMonth = src.getString("billMonth", "");
            int billYear = src.getInt("billYear", 0);
            int rsid = src.getInt("rsid");
            JsonObjectBuilder b = Json.createObjectBuilder();
            System.out.println("Json builder: "+b);
            b.add("due_year_month", isoDateFromMonthYear(billMonth, billYear));
            b.add("paymentDate", isoDateFromMonthYear(billMonth, billYear));
            b.add("full_name", customerName);
            b.add("orgName", orgLabel);
            b.add("amount", total);
            b.add("invoice_number", billId);
            b.add("accountNumber", bankAccount);
            b.add("accountId", accountId);
            b.add("customer_id", accountId);
            b.add("code", "");
            b.add("rsid", rsid);
            normalizedArr.add(b.build());
        }

        return Json.createObjectBuilder()
                .add("success", true)
                .add("data", normalizedArr.build())
                .build()
                .toString();
    }
    private String getJsonValueAsString(JsonObject obj, String key) {
        if (obj == null || !obj.containsKey(key) || obj.isNull(key)) {
            return "";
        }
        JsonValue value = obj.get(key);
        if (value.getValueType() == JsonValue.ValueType.STRING) {
            return obj.getString(key);
        }
        if (value.getValueType() == JsonValue.ValueType.NUMBER) {
            return obj.getJsonNumber(key).toString();
        }
        return value.toString();
    }

    private String isoDateFromMonthYear(String monthName, int year) {
        try {
            int monthNumber = java.time.Month.valueOf(monthName.trim().toUpperCase()).getValue();
            return String.format("%04d-%02d-01T00:00:00+03:00", year, monthNumber);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not parse Geez billMonth/billYear: " + monthName + " " + year, e);
            return monthName + " " + year;
        }
    }

    private Response.ResponseBuilder handleUnpaidBills(RequestResponseCol reqres, act.com.models.Response res) {

        try {
            LOGGER.info("------------------------- HANDLING DISPLAY UNPAID BILLS ---------------------");
            String currentRequest = reqres.getCurrentRequest().trim();
            if ("#".equals(currentRequest) || "*".equals(currentRequest)) {
                return handlePaginationNavigation(reqres, res);
            }

            LOGGER.info("------------------ SHOWING UNPAID BILLS -----------------------");
            int userSelection;
            try {
                userSelection = Integer.parseInt(currentRequest);
            } catch (NumberFormatException e) {
                SessionManager.removeSession(reqres.getSessionId());
                res.setAction("end");
                LOGGER.log(Level.SEVERE, "ERROR: ", e);
                return buildResponse(reqres, "Invalid input. Please enter a number, '*' for previous, or '#' for next.", res);
            }
            if (userSelection != 1) {
                SessionManager.removeSession(reqres.getSessionId());
                res.setAction("end");
                return buildResponse(reqres, "Invalid Selection. Please try again", res);
            }

            LOGGER.info("------------------ GETTING BILLS FROM SESSION -----------------------");
            String billsResponseString = reqres.getUnpaidBills();

            if (billsResponseString == null || billsResponseString.isEmpty()) {
                SessionManager.removeSession(reqres.getSessionId());
                res.setAction("end");
                return buildResponse(reqres, "No payment data found.", res);
            }

            JsonArray responseArr = getBillsArray(billsResponseString);
            LOGGER.info("------------------ SHOWING UNPAID BILLS IN ARRAY :" + responseArr);

            reqres.setCurrentPage(1);

            if (responseArr.size() == 1) {
                JsonObject paymentDetail = responseArr.getJsonObject(0);

                String paymentMonth = formatMonth(paymentDetail.getString("due_year_month"));
                String customerName = paymentDetail.getString("full_name");
                String orgName = paymentDetail.getString("orgName");
                double totalPayment = calculateTotalPayment(paymentDetail);

                reqres.setSelectedPaymentMonthSize(1);
                reqres.setSingle(true);
                reqres.setTotalAmount(totalPayment);
                SessionManager.storeSession(reqres.getSessionId(), reqres);

                String message = UssdMessageFormatter.buildSingleBillMessage(paymentMonth, customerName, totalPayment, orgName);

                res.setAction("request");
                return buildResponse(reqres, message, res);
            } else {
                SessionManager.storeSession(reqres.getSessionId(), reqres);
                return handleUPSDisplay(reqres, res, reqres);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "PAGINATION ERROR", e);
            SessionManager.removeSession(reqres.getSessionId());
            res.setAction("end");
            return buildResponse(reqres, "Unable To Load Bills", res);
        }
    }

    private javax.ws.rs.core.Response.ResponseBuilder displayUnpaidPayments(RequestResponseCol reqres,
                                                                            act.com.models.Response res) {
        LOGGER.info("--------------------- HANDLING DISPLAY UNPAID PAYMENTS ---------------------");
        ResponseBuilder builder = null;
        String billsResponseString = reqres.getUnpaidBills();

        WaterProvider provider = safeProvider(reqres.getPaymentProvider());
        JsonArray responseArr = null;
        if(provider == WaterProvider.GEEZ){
            responseArr = sortBillsByRsid(getBillsArray(billsResponseString));
        }else{
            responseArr = sortBillsByDueDate(getBillsArray(billsResponseString));
        }
        if (responseArr != null && !responseArr.isEmpty()) {
            try {
                int userInput = Integer.parseInt(reqres.getCurrentRequest());

                if (responseArr.size() == 1) {
                    if (userInput == 1) {
                        JsonObject responseDetail = responseArr.getJsonObject(0);
                        String paymentMonth = formatMonth(responseDetail.getString("due_year_month"));
                        String customerName =
                                UssdMessageFormatter.cleanCustomerName(
                                        responseArr.getJsonObject(0).getString("full_name"));
                        String orgName = responseDetail.getString("orgName", "");
                        double totalPayment = calculateTotalPayment(responseDetail);

                        reqres.setSelectedPaymentMonthSize(userInput);
                        reqres.setTotalAmount(totalPayment);
                        SessionManager.storeSession(reqres.getSessionId(), reqres);

                        res.setAction("request");
                        String message =
                                UssdMessageFormatter.buildConfirmationMessage(
                                        paymentMonth,
                                        customerName,
                                        totalPayment,
                                        orgName);

                        builder = buildResponse(reqres, message, res);
                    } else {
                        res.setAction("end");
                        SessionManager.removeSession(reqres.getSessionId());
                        builder = buildResponse(reqres, "Invalid Input. Please try again!", res);
                    }
                } else if (responseArr.size() > 1) {
                    if (userInput > responseArr.size() || userInput < 0) {
                        res.setAction("end");
                        SessionManager.removeSession(reqres.getSessionId());
                        builder = buildResponse(reqres, "Invalid Input. Please try again!", res);
                    } else {
                        if (userInput == 0) {
                            double totalPayment = 0;
                            for (int i = 0; i < responseArr.size(); i++) {
                                totalPayment += calculateTotalPayment(responseArr.getJsonObject(i));
                            }
                            String fromMonth = formatMonth(responseArr.getJsonObject(0).getString("due_year_month"));
                            String toMonth = formatMonth(responseArr.getJsonObject(responseArr.size() - 1).getString("due_year_month"));
                            String customerName =
                                    UssdMessageFormatter.cleanCustomerName(
                                            responseArr.getJsonObject(0).getString("full_name"));
                            String orgName = responseArr.getJsonObject(0).getString("orgName");
                            reqres.setSelectedPaymentMonthSize(userInput);
                            reqres.setTotalAmount(totalPayment);
                            SessionManager.storeSession(reqres.getSessionId(), reqres);
                            res.setAction("request");

                            String message =
                                    UssdMessageFormatter.buildAllConfirmationMessage(
                                            fromMonth,
                                            toMonth,
                                            customerName,
                                            totalPayment,
                                            orgName);

                            builder = buildResponse(reqres, message, res);
                        } else if (userInput == 1) {
                            JsonObject paymentDetail = responseArr.getJsonObject(0);
                            String paymentMonth = formatMonth(paymentDetail.getString("due_year_month"));
                            String customerName = responseArr.getJsonObject(0).getString("full_name");
                            String orgName = responseArr.getJsonObject(0).getString("orgName");

                            double paymentAmount = calculateTotalPayment(paymentDetail);

                            reqres.setSelectedPaymentMonthSize(userInput);
                            reqres.setTotalAmount(paymentAmount);
                            SessionManager.storeSession(reqres.getSessionId(), reqres);
                            res.setAction("request");

                            String message =
                                    UssdMessageFormatter.buildConfirmationMessage(
                                            paymentMonth,
                                            customerName,
                                            paymentAmount,
                                            orgName);

                            builder = buildResponse(reqres, message, res);

                        } else {
                            double totalSelectedPayment = 0;
                            String fromMonth = "";
                            String toMonth = "";
                            for (int index = 0; index < userInput; index++) {
                                JsonObject paymentDetail = responseArr.getJsonObject(index);
                                double paymentAmount = calculateTotalPayment(paymentDetail);
                                totalSelectedPayment += paymentAmount;
                            }
                            fromMonth = formatMonth(responseArr.getJsonObject(0).getString("due_year_month"));
                            toMonth = formatMonth(responseArr.getJsonObject(userInput - 1).getString("due_year_month"));

                            String customerName =
                                    UssdMessageFormatter.cleanCustomerName(
                                            responseArr.getJsonObject(0).getString("full_name"));
                            String orgName = responseArr.getJsonObject(0).getString("orgName");

                            reqres.setSelectedPaymentMonthSize(userInput);
                            reqres.setTotalAmount(totalSelectedPayment);
                            SessionManager.storeSession(reqres.getSessionId(), reqres);

                            res.setAction("request");
                            String message =
                                    UssdMessageFormatter.buildRangeConfirmationMessage(
                                            fromMonth,
                                            toMonth,
                                            customerName,
                                            totalSelectedPayment,
                                            orgName);

                            builder = buildResponse(reqres, message, res);
                        }
                    }
                } else {
                    SessionManager.removeSession(reqres.getSessionId());
                    res.setAction("end");
                    builder = buildResponse(reqres, "No unpaid months found. Please check the payment code and try again.", res);
                }
            } catch (Exception e) {
                e.printStackTrace();
                res.setAction("end");
                LOGGER.log(Level.SEVERE, "ERROR: ", e);
                SessionManager.removeSession(reqres.getSessionId());
                builder = buildResponse(reqres, "System error. Please try again!", res);
            }
        } else {
            res.setAction("end");
            SessionManager.removeSession(reqres.getSessionId());
            builder = buildResponse(reqres, "Session data not found. Please try again.", res);
        }
        return builder;
    }

    private javax.ws.rs.core.Response.ResponseBuilder processPaymentSelection(RequestResponseCol reqres,
                                                                              act.com.models.Response res) {
        LOGGER.info("------------------ PROCESSING PAYMENT SELECTION -----------------------");

        ResponseBuilder builder = null;
        try {
            int selection = Integer.parseInt(reqres.getCurrentRequest());
            if (selection == 1) {
                String message = this.getLanguageString(reqres.getLanguage(), "pass");
                res.setAction("request");
                builder = buildResponse(reqres, message, res);
            } else {
                String message = this.getLanguageString(reqres.getLanguage(), "inv");
                SessionManager.removeSession(reqres.getSessionId());
                res.setAction("end");
                builder = buildResponse(reqres, message, res);
            }
        } catch (Exception e) {
            res.setAction("end");
            SessionManager.removeSession(reqres.getSessionId());
            LOGGER.log(Level.SEVERE, "Exception while processing transaction{} ", e.getMessage());
            builder = buildResponse(reqres, "Something went wrong, please try again", res);
        }
        return builder;
    }

    private javax.ws.rs.core.Response.ResponseBuilder paymentCompletion(RequestResponseCol reqres,
                                                                        act.com.models.Response res, List<CustomerInfoView> customerAccounts) throws Exception {
        ResponseBuilder builder = null;
        LOGGER.info("-------------------------- HANDLING PAYMENT COMPLETION --------------------");
        try {
            LOGGER.info("------------------------ AUTHENTICATING THE USER WITH PHONE NUMBER: " + reqres.getMsisdn());
            String pin = reqres.getCurrentRequest();
            if (pin.isEmpty()) {
                String message = this.getLanguageString(reqres.getLanguage(), "inv");
                SessionManager.removeSession(reqres.getSessionId());
                res.setAction("end");
                return buildResponse(reqres, message, res);
            }
            String keyCloakURL = config.getProperty("keyloack.authenticate");
            Client authClient = ClientBuilder.newClient();
            JsonObject obj = Json.createObjectBuilder()
                    .add("phone", reqres.getMsisdn())
                    .add("password", reqres.getCurrentRequest())
                    .build();

            Response response = authClient
                    .target(keyCloakURL)
                    .request(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Basic ")
                    .post(Entity.json(obj));

            int responseAuthStatus = response.getStatus();
            LOGGER.info("----------------------------- AUTHENTICATING USER RESPONSE CODE: " + responseAuthStatus);

            if (responseAuthStatus == 200) {
                JsonObject jsonResp = Json.createReader(new StringReader(response.readEntity(String.class))).readObject();
                if (jsonResp.getBoolean("status")) {
                    String billsResponseString = reqres.getUnpaidBills();
                    LOGGER.info("----------------------------- AFTER GETTING BILL RESPONSE: " + billsResponseString);

                    WaterProvider provider = safeProvider(reqres.getPaymentProvider());
                    JsonArray responseArray = null;
                    if(provider == WaterProvider.GEEZ){
                        responseArray = sortBillsByRsid(getBillsArray(billsResponseString));
                    }else{
                        responseArray = sortBillsByDueDate(getBillsArray(billsResponseString));
                    }
                    LOGGER.info("----------------------------- AFTER GETTING RESPONSE ARRAY: " + responseArray);

                    if (responseArray != null && !responseArray.isEmpty()) {
                        JsonObject firstObj = responseArray.getJsonObject(0);

                        int selectedPaymentMonthSize = reqres.getSelectedPaymentMonthSize();

                        int userInput = Integer.parseInt((reqres.getRequestList().get(2).getUssdrequestString()));

                        String debitAccount = customerAccounts.get(userInput - 1).getAccount();

                        Double totalAmount = reqres.getTotalAmount();

                        JsonArray invoiceNumber = null;
                        JsonArrayBuilder builderForPayment = Json.createArrayBuilder();
                        StringBuilder paymentMonth = new StringBuilder();
                        if (selectedPaymentMonthSize == 0) {
                            for (int i = 0; i < responseArray.size(); i++) {
                                JsonObject paymentDetail = responseArray.getJsonObject(i);
                                builderForPayment.add(paymentDetail.getString("invoice_number"));
                                if (i == 0)
                                    paymentMonth.append(formatMonth(paymentDetail.getString("due_year_month")));
                                else
                                    paymentMonth.append("*").append(formatMonth(paymentDetail.getString("due_year_month")));
                            }

                        } else {
                            for (int i = 0; i < selectedPaymentMonthSize && i < responseArray.size(); i++) {
                                JsonObject paymentDetail = responseArray.getJsonObject(i);
                                builderForPayment.add(paymentDetail.getString("invoice_number"));
                                if (i == 0)
                                    paymentMonth.append(formatMonth(paymentDetail.getString("due_year_month")));
                                else
                                    paymentMonth.append("*").append(formatMonth(paymentDetail.getString("due_year_month")));
                            }

                        }
                        invoiceNumber = builderForPayment.build();

                        if (provider == WaterProvider.GEEZ) {
                            return completeGeezPayment(reqres, res, customerAccounts, firstObj, invoiceNumber, debitAccount, totalAmount);
                        }
                        return completeDerashOrUpsPayment(reqres, res, customerAccounts, firstObj, invoiceNumber,
                                debitAccount, totalAmount, paymentMonth, responseArray, selectedPaymentMonthSize);

                    } else {
                        res.setAction("end");
                        SessionManager.removeSession(reqres.getSessionId());
                        return buildResponse(reqres, "No data found on session", res);
                    }
                } else {
                    res.setAction("end");
                    SessionManager.removeSession(reqres.getSessionId());
                    return buildResponse(reqres, "Invalid pin", res);
                }
            } else {
                LOGGER.info("---------------------------- AUTHENTICATED USER RESPONSE JSON: " );
                JsonObject jsonResp = Json.createReader(new StringReader(response.readEntity(String.class))).readObject();
                LOGGER.info("---------------------------- AUTHENTICATED USER RESPONSE JSON: " + jsonResp);

                String message = jsonResp.getString("message");
                String result = message != null ? message : "Something went wrong";
                res.setAction("end");

                SessionManager.removeSession(reqres.getSessionId());
                LOGGER.severe("ERROR WHILE AUTHENTICATING USERS: ");
                return buildResponse(reqres, result, res);
            }
        } catch (Exception e) {
            e.printStackTrace();
            res.setAction("end");
            LOGGER.log(Level.SEVERE, "ERROR WHILE AUTHENTICATING USER: ", e);
            SessionManager.removeSession(reqres.getSessionId());
            return buildResponse(reqres, "Something went wrong, please try again", res);
        }
    }

    private WaterProvider safeProvider(String providerName) {
        try {
            return WaterProvider.valueOf(providerName);
        } catch (Exception e) {
            return WaterProvider.UPS; // fall back default behaviour
        }
    }

    private boolean isInquiryLoggingEnabled() {
        String prop = config.getProperty("log.paymentCodeInquiry.enabled", "true");
        return Boolean.parseBoolean(prop == null ? "true" : prop.trim());
    }

    private void logPaymentCodeValidation(RequestResponseCol reqres, WaterProvider provider, boolean valid, String reason) {
        if (!isInquiryLoggingEnabled()) {
            return;
        }
        try {
            PaymentCodeValidationLog log = new PaymentCodeValidationLog();
            log.setSessionId(reqres.getSessionId());
            log.setMsisdn(reqres.getMsisdn());
            log.setProvider(provider == null ? null : provider.name());
            log.setBillType(reqres.getBillType());
            log.setPaymentCode(reqres.getPaymentCode());
            log.setStatus(valid ? "VALID" : "INVALID");
            log.setReason(reason);
            paymentCodeValidationLogDao.save(log);
        } catch (Exception e) {
            e.printStackTrace();
            LOGGER.log(Level.WARNING, "Could not log payment code validation (non-fatal)", e.getMessage());
        }
    }

    private ResponseBuilder completeDerashOrUpsPayment(RequestResponseCol reqres, act.com.models.Response res,
                                                       List<CustomerInfoView> customerAccounts, JsonObject firstObj,
                                                       JsonArray invoiceNumber, String debitAccount, Double totalAmount,
                                                       StringBuilder paymentMonth, JsonArray responseArray,
                                                       int selectedPaymentMonthSize) throws Exception {

        String creditAccount = firstObj.getString("accountNumber");
        String orgCode = firstObj.getString("code");
        String paymentCode = reqres.getPaymentCode();
        String payerName = firstObj.getString("full_name");
        String organizationName = firstObj.getString("orgName");
        String billType = reqres.getBillType();

        JsonObject paymentObj = Json.createObjectBuilder()
                .add("debitAccount", debitAccount)
                .add("creditAccount", creditAccount)
                .add("billType", billType)
                .add("masterId", paymentCode)
                .add("invoiceNumber", invoiceNumber)
                .add("amount", totalAmount)
                .add("phone", reqres.getMsisdn())
                .add("source", "USSD")
                .add("remark", "Bill Payment")
                .add("payerName", payerName)
                .add("organizationName", organizationName)
                .add("paymentMonth", String.valueOf(paymentMonth))
                .add("code", orgCode)
                .build();

        System.out.println("**************** Payment payload: " + paymentObj);

        String accessToken = getAccessToken();
        String paymentURL = config.getProperty("ups.payment");

        Response paymentResponse = client
                .target(paymentURL)
                .request(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .post(Entity.json(paymentObj));

        JsonObject paymentStatus = Json.createReader(new StringReader(paymentResponse.readEntity(String.class))).readObject();

        boolean success = paymentStatus.getBoolean("success", false);
        String apiMessage = paymentStatus.getString("message", "");
        String cbsRef = paymentStatus.getString("cbsRef", "");
        String accountHolder =
                UssdMessageFormatter.cleanCustomerName(
                        customerAccounts.get(0).getAccountHolder());

        System.out.println("**************** Payment response: " + paymentStatus);

        String fromMonth = formatMonth(firstObj.getString("due_year_month"));
        String toMonth = "";
        if (success) {
            String paymentPeriod;
            if (selectedPaymentMonthSize == 1) {
                paymentPeriod = fromMonth;
            } else if (selectedPaymentMonthSize == 2) {
                toMonth = formatMonth(responseArray.getJsonObject(1).getString("due_year_month"));
                paymentPeriod = fromMonth + " & " + toMonth;
            } else if (selectedPaymentMonthSize > 2) {
                toMonth = formatMonth(responseArray.getJsonObject(selectedPaymentMonthSize - 1).getString("due_year_month"));
                paymentPeriod = fromMonth + "-" + toMonth;
            } else {
                paymentPeriod = paymentMonth.toString().replace("*", ", ");
            }
            String message = UssdMessageFormatter.buildSuccessMessage(
                    accountHolder,
                    firstObj.getString("full_name"),
                    firstObj.getString("orgName"),
                    paymentPeriod,
                    totalAmount,
                    cbsRef,
                    firstObj.getString("customer_id")
            );
            res.setAction("end");
            SessionManager.removeSession(reqres.getSessionId());
            return buildResponse(reqres, message, res);
        }

        res.setAction("end");
        SessionManager.removeSession(reqres.getSessionId());
        return buildResponse(reqres, apiMessage, res);
    }


    private ResponseBuilder completeGeezPayment(RequestResponseCol reqres, act.com.models.Response res,
                                                List<CustomerInfoView> customerAccounts, JsonObject firstObj,
                                                JsonArray invoiceNumber, String debitAccount, Double totalAmount) throws Exception {

        String bankAccount = firstObj.getString("accountNumber","");
        String accountId = firstObj.getString("accountId", "");

        JsonObject geezPaymentObj = Json.createObjectBuilder()
                .add("billIds", invoiceNumber)
                .add("totalAmount", String.valueOf(totalAmount))
                .add("bankAccount", bankAccount)
                .add("debitAccount", debitAccount)
                .add("accountId", accountId)
                .build();

        System.out.println("**************** Geez payment payload: " + geezPaymentObj);

        String accessToken = getAccessToken();
        String paymentURL = config.getProperty("geez.payment");
        Response paymentResponse = client
                .target(paymentURL)
                .request(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .put(Entity.json(geezPaymentObj));

        JsonObject paymentStatus = Json.createReader(new StringReader(paymentResponse.readEntity(String.class))).readObject();
        System.out.println("**************** Geez payment response: " + paymentStatus);
        System.out.println("**************** Geez payment status code: " + paymentResponse.getStatus());

        boolean success = "0".equals(getJsonValueAsString(paymentStatus, "status"));

        if (success) {
            String cbsRef = paymentStatus.getString("referenceNo", "");
            String accountHolder = UssdMessageFormatter.cleanCustomerName(customerAccounts.get(0).getAccountHolder());
            String message = UssdMessageFormatter.buildSuccessMessage(
                    accountHolder,
                    firstObj.getString("full_name", ""),
                    firstObj.getString("orgName", "Geez Water Supply"),
                    formatMonth(firstObj.getString("due_year_month", "")),
                    totalAmount,
                    cbsRef,
                    firstObj.getString("customer_id", "")
            );
            res.setAction("end");
            return buildResponse(reqres, message, res);
        }

        String apiMessage = paymentStatus.getString("description", "Something went wrong, please try again");
        res.setAction("end");
        SessionManager.removeSession(reqres.getSessionId());

        return buildResponse(reqres, apiMessage, res);
    }

    private double calculateTotalPayment(JsonObject paymentDetail) {
        return getDouble(paymentDetail, "amount");
    }

    private double getDouble(JsonObject obj, String key) {
        try {
            if (obj.get(key) instanceof JsonNumber) {
                return ((JsonNumber) obj.get(key)).doubleValue();
            } else {
                return Double.parseDouble(obj.getString(key));
            }
        } catch (Exception e) {
            return 0.0;
        }
    }

    private JsonObject buildBillInquiryRequest(RequestResponseCol session) {

        String bill = session.getBillType();
        String paymentCode = session.getPaymentCode();
        LOGGER.info("------------------------ inside build bill inquiry request ------------------------");
        LOGGER.info("------------------------------ BillType: " + bill);
        LOGGER.info("------------------------------ PaymentCode: " + paymentCode);
        javax.json.JsonObjectBuilder builder = Json.createObjectBuilder()
                .add("billType", bill)
                .add("paymentCode", paymentCode);

        LOGGER.info("------------------------ inside build bill inquiry request after builder ------------------------");

        String organizationCode = session.getOrganizationCode();
        if (organizationCode != null && !organizationCode.isEmpty()) {
            builder.add("organizationCode", organizationCode);
        }
        return builder.build();
    }

    private ResponseBuilder handleUPSDisplay(RequestResponseCol reqres, act.com.models.Response res,
                                             RequestResponseCol sessionState) {
        LOGGER.info("################################ UNDER UPS DISPLAY METHOD ###########################");
        try {
            String unpaidBills = sessionState.getUnpaidBills();

            if (unpaidBills == null || unpaidBills.isEmpty()) {
                SessionManager.removeSession(reqres.getSessionId());
                res.setAction("end");
                return buildResponse(reqres, "No payment data found.", res);
            }

            WaterProvider provider = safeProvider(reqres.getPaymentProvider());

            JsonArray unpaidBillsArray = null;
            if(provider == WaterProvider.GEEZ){
                unpaidBillsArray = sortBillsByRsid(getBillsArray(unpaidBills));
            }else{
                unpaidBillsArray = sortBillsByDueDate(getBillsArray(unpaidBills));
            }

            List<Integer> pageBreaks = computePageBreaks(unpaidBillsArray);
            int totalPages = pageBreaks.size();

            int currentPage = sessionState.getCurrentPage() != null ? sessionState.getCurrentPage() : 1;
            currentPage = Math.min(currentPage, totalPages);
            sessionState.setCurrentPage(currentPage);

            int startIndex = currentPage == 1 ? 0 : pageBreaks.get(currentPage - 2);
            int endIndex = pageBreaks.get(currentPage - 1);

            StringBuilder monthsList = new StringBuilder();
            for (int i = startIndex; i < endIndex; i++) {
                monthsList.append(buildBillLine(i + 1, unpaidBillsArray.getJsonObject(i)));
            }

            if (currentPage > 1) monthsList.append("*.Back\n");
            if (currentPage < totalPages) monthsList.append("#.Next\n");
            monthsList.append(buildAllOptionFooter());
            StringBuilder message = new StringBuilder();
            if (currentPage == 1) {
                double totalPayment = 0;
                for (JsonValue v : unpaidBillsArray) {
                    totalPayment += calculateTotalPayment((JsonObject) v);
                }

                message.append(unpaidBillsArray.size())
                        .append(" unpaid months\n")
                        .append("Total ")
                        .append(UssdMessageFormatter.formatAmount(totalPayment))
                        .append("ETB\n");
            }

            res.setAction("request");
            return buildResponse(reqres, message.toString() + monthsList.toString(), res);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "ERROR: ", e);
            e.printStackTrace();
            SessionManager.removeSession(reqres.getSessionId());
            res.setAction("end");
            return buildResponse(reqres, "Error displaying payments.. Please try again.", res);
        }
    }

    private ResponseBuilder handlePaginationNavigation(RequestResponseCol reqres, act.com.models.Response res) {

        RequestResponseCol sessionState = SessionManager.getSession(reqres.getSessionId());
        if (sessionState == null) {
            SessionManager.removeSession(reqres.getSessionId());
            res.setAction("end");
            return buildResponse(reqres, "Session expired. Please start again.", res);
        }

        int currentPage = sessionState.getCurrentPage() != null ? sessionState.getCurrentPage() : 1;

        if ("#".equals(reqres.getCurrentRequest())) {
            currentPage = currentPage + 1;
        } else if ("*".equals(reqres.getCurrentRequest())) {
            currentPage = Math.max(1, currentPage - 1);
        }

        sessionState.setCurrentPage(currentPage);
        SessionManager.storeSession(reqres.getSessionId(), sessionState);

        return handleUPSDisplay(reqres, res, sessionState);
    }

    private JsonArray getBillsArray(String unpaidBills) {
        LOGGER.info("----------------------------- INSIDE AFTER CALLING GET BILLS ARRAY() ----------- ");
        JsonReader reader = Json.createReader(new StringReader(unpaidBills));
        JsonObject obj = reader.readObject();
        return obj.getJsonArray("data");
    }

    private JsonArray sortBillsByDueDate(JsonArray bills) {

        List<JsonObject> billList = new ArrayList<>();

        for (JsonValue value : bills) {
            billList.add((JsonObject) value);
        }

        billList.sort(Comparator.comparing(bill -> YearMonth.from(
                OffsetDateTime.parse(bill.getString("paymentDate"))
        )));

        JsonArrayBuilder builder = Json.createArrayBuilder();

        for (JsonObject bill : billList) {
            builder.add(bill);
        }

        return builder.build();
    }

    private JsonArray sortBillsByRsid(JsonArray bills) {

        List<JsonObject> billList = new ArrayList<>();

        for (JsonValue value : bills) {
            billList.add((JsonObject) value);
        }

        billList.sort(Comparator.comparingInt(
                bill -> bill.getInt("rsid")
        ));

        JsonArrayBuilder builder = Json.createArrayBuilder();

        for (JsonObject bill : billList) {
            builder.add(bill);
        }

        return builder.build();
    }

    private String formatMonth(String dateString) {

        try {
            OffsetDateTime date =
                    OffsetDateTime.parse(dateString);

            return date.format(
                    DateTimeFormatter.ofPattern("MMMM yyyy")
            );
        } catch (Exception e) {
            return dateString;
        }
    }

    private javax.ws.rs.core.Response.ResponseBuilder buildResponse(RequestResponseCol reqres, String message,
                                                                    act.com.models.Response res) {
        javax.ws.rs.core.Response.ResponseBuilder builder;
        res.setUssdresponseString(message);
        res.setTransactionId(reqres.getSessionId());
        Date date = new Date();
        res.setTransactionTime(((Long) date.getTime()).toString());
        builder = javax.ws.rs.core.Response.ok().entity(res);
        return builder;
    }

    public String getLanguageString(String lang, String stringid) throws Exception {
        String userDisplay = "";
        Query q = this.em.createNamedQuery("GetMenu");
        q.setParameter("language", lang);
        q.setParameter("stringid", stringid);
        if (q.getResultList().size() > 0) {
            userDisplay = ((L10n) q.getSingleResult()).getContent();
        } else {
            Query q1 = this.em.createNamedQuery("GetMenu");
            q1.setParameter("language", "ENG");
            q1.setParameter("stringid", stringid);
            if (q1.getResultList().size() <= 0) {
                throw new Exception("ACT-ERROR-DATA Menu does not exist");
            }
            userDisplay = ((L10n) q1.getSingleResult()).getContent();
        }
        return userDisplay;
    }

    public List<String> optionfinder(String options) {
        List<String> menu = new ArrayList<>();
        menu.add("Heading");
        try {
            String[] optionList = options.split("\n");
            if (optionList.length > 0) {
                int counter = 0;
                for (String option : optionList) {
                    if (counter > 0) {
                        String[] row = option.split("\\.");
                        int index = Integer.parseInt(row[0]);
                        menu.add(index, row[1]);
                    }
                    counter++;
                }
                return menu;
            } else {
                return menu;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return menu;
        }
    }

    public static String getAccessToken() throws Exception {
        String grantType = "grant_type=client_credentials";

        String USERNAME = config.getProperty("auth.username");
        String PASSWORD = config.getProperty("auth.password");
        String URL = config.getProperty("accessTokenUrl");


        HttpURLConnection connection = (HttpURLConnection) new URL(URL).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);

        String auth = USERNAME + ":" + PASSWORD;
        connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(auth.getBytes()));
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

        try (OutputStream os = connection.getOutputStream()) {
            os.write(grantType.getBytes());
            os.flush();
        }

        int responseCode = connection.getResponseCode();
        if (responseCode == 200) {
            try (InputStream is = connection.getInputStream()) {
                Scanner scanner = new Scanner(is).useDelimiter("\\A");
                String response = scanner.hasNext() ? scanner.next() : "";

                JSONObject jsonResponse = new JSONObject(response);
                String accessToken = jsonResponse.getString("access_token");

                return accessToken;
            }
        } else {
            throw new Exception("Failed to fetch token. HTTP Code: " + responseCode);
        }
    }

    private String buildBillLine(int index, JsonObject bill) {
        String month = UssdMessageFormatter.shortMonth(formatMonth(bill.getString("due_year_month")));
        String amount = UssdMessageFormatter.formatAmount(calculateTotalPayment(bill));
        return index + ". " + month + " : " + amount + " ETB\n";
    }

    private String buildAllOptionFooter() {
        return "0.All\n1/2/3 = First 1/2/3";
    }

    private int computeFooterReserve() {
        String worstCaseFooter = "*.Back\n" + "#.Next\n" + buildAllOptionFooter();
        return worstCaseFooter.length();
    }

    private List<Integer> computePageBreaks(JsonArray bills) {
        List<Integer> pageBreaks = new ArrayList<>();

        boolean hasUnicode = false;
        for (JsonValue v : bills) {
            JsonObject b = (JsonObject) v;
            if (UssdMessageFormatter.containsAmharic(b.getString("full_name", ""))
                    || UssdMessageFormatter.containsAmharic(b.getString("orgName", ""))) {
                hasUnicode = true;
                break;
            }
        }

        int footerReserve = computeFooterReserve();
        int maxLen = (hasUnicode
                ? UssdMessageFormatter.getMaxUssdLength("የ")
                : UssdMessageFormatter.getMaxUssdLength("A"))
                - footerReserve;

        int used = 0;
        int start = 0;
        for (int i = 0; i < bills.size(); i++) {
            String month = formatMonth(bills.getJsonObject(i).getString("due_year_month"));
            String amount = UssdMessageFormatter.formatAmount(calculateTotalPayment(bills.getJsonObject(i)));
            String line = (i + 1) + ". " + month + " : " + amount + " ETB\n";

            if (used + line.length() > maxLen && i > start) {
                pageBreaks.add(i);
                start = i;
                used = 0;
            }
            used += line.length();
        }

        pageBreaks.add(bills.size());
        return pageBreaks;
    }
}