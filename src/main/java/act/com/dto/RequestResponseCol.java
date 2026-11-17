package act.com.dto;

import act.com.models.Request;
import act.com.models.Response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RequestResponseCol {
    private String sessionId;
    private String msisdn;
    private int currentItteration;
    private String headMenu;
    private String currentRequest;
    private String language;
    private String billType;
    private String organizationCode;
    private String paymentCode;
    private String unpaidBills;
    private boolean requiresOrganizationCode;

    public String getPaymentProvider() {
        return paymentProvider;
    }

    public void setPaymentProvider(String paymentProvider) {
        this.paymentProvider = paymentProvider;
    }

    private String paymentProvider;

    public Integer getCurrentIndex() {
        return currentIndex;
    }

    public void setCurrentIndex(Integer currentIndex) {
        this.currentIndex = currentIndex;
    }

    private Integer currentIndex;

    public boolean isRequiresOrganizationCode() {
        return requiresOrganizationCode;
    }

    public void setRequiresOrganizationCode(boolean requiresOrganizationCode) {
        this.requiresOrganizationCode = requiresOrganizationCode;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    private String remark;

    public Double getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(Double totalAmount) {
        this.totalAmount = totalAmount;
    }

    private Double totalAmount;

    public boolean isSingle() {
        return isSingle;
    }

    public void setSingle(boolean single) {
        isSingle = single;
    }

    boolean isSingle;

    public int getSelectedPaymentMonthSize() {
        return selectedPaymentMonthSize;
    }

    public void setSelectedPaymentMonthSize(int selectedPaymentMonthSize) {
        this.selectedPaymentMonthSize = selectedPaymentMonthSize;
    }

    int selectedPaymentMonthSize;

    public Integer getCurrentPage() {
        return currentPage;
    }

    public void setCurrentPage(Integer currentPage) {
        this.currentPage = currentPage;
    }

    private Integer currentPage;

    public String getUnpaidBills() {
        return unpaidBills;
    }

    public void setUnpaidBills(String unpaidBills) {
        this.unpaidBills = unpaidBills;
    }

    public String getOrganizationCode() {
        return organizationCode;
    }

    public void setOrganizationCode(String organizationCode) {
        this.organizationCode = organizationCode;
    }

    public String getPaymentCode() {
        return paymentCode;
    }

    public void setPaymentCode(String paymentCode) {
        this.paymentCode = paymentCode;
    }

    public int getSelectedPayment() {
        return selectedPayment;
    }

    public void setSelectedPayment(int selectedPayment) {
        this.selectedPayment = selectedPayment;
    }

    private int selectedPayment;

    public String getBillType() {
        return billType;
    }

    public void setBillType(String billType) {
        this.billType = billType;
    }


    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    private String account;

    private List<Request> requestList = new ArrayList<Request>();
    private List<Response> responseList = new ArrayList<Response>();


    public String getSessionId() {
        return sessionId;
    }

    public List<Request> getRequestList() {
        return requestList;
    }

    public void setRequestList(List<Request> requestList) {
        this.requestList = requestList;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getMsisdn() {
        return msisdn;
    }

    public void setMsisdn(String msisdn) {
        this.msisdn = msisdn;
    }

    public int getCurrentItteration() {
        return currentItteration;
    }

    public void setCurrentItteration(int currentItteration) {
        this.currentItteration = currentItteration;
    }

    public String getHeadMenu() {
        return headMenu;
    }

    public void setHeadMenu(String headMenu) {
        this.headMenu = headMenu;
    }

    public String getCurrentRequest() {
        return currentRequest;
    }

    public void setCurrentRequest(String currentRequest) {
        this.currentRequest = currentRequest;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public List<Response> getResponseList() {
        return responseList;
    }

    public void setResponseList(List<Response> responseList) {
        this.responseList = responseList;
    }

}