package act.com.models;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.io.Serializable;

@Entity
@Table(name = "customerInfoView")
public class CustomerInfoView implements Serializable {

    @Column(name = "phone")
    private String phone;
    @Column(name = "branch")
    private String branch;
    @Id
    @Column(name = "account")
    private String account;

    public String getAccountHolder() {
        return accountHolder;
    }

    @Column(name = "full_name")
    private String accountHolder;

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }
}
