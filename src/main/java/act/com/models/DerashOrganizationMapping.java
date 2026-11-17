package act.com.models;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.Table;
import java.io.Serializable;


@Entity
@Table(name = "derash_organization_mapping")
@NamedQueries({
        @NamedQuery(
                name = "DerashOrganizationMapping.findByCode",
                query = "SELECT d FROM DerashOrganizationMapping d WHERE d.code = :code"
        )
})
public class DerashOrganizationMapping implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @Column(name = "code", length = 3, nullable = false)
    private String code;

    @Column(name = "organization_code", length = 50, nullable = false)
    private String organizationCode;

    @Column(name = "organization_name", length = 150)
    private String organizationName;

    @Column(name = "city_name", length = 100)
    private String cityName;

    public DerashOrganizationMapping() {
    }

    public DerashOrganizationMapping(String code, String organizationCode, String organizationName, String cityName) {
        this.code = code;
        this.organizationCode = organizationCode;
        this.organizationName = organizationName;
        this.cityName = cityName;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getOrganizationCode() {
        return organizationCode;
    }

    public void setOrganizationCode(String organizationCode) {
        this.organizationCode = organizationCode;
    }

    public String getOrganizationName() {
        return organizationName;
    }

    public void setOrganizationName(String organizationName) {
        this.organizationName = organizationName;
    }

    public String getCityName() {
        return cityName;
    }

    public void setCityName(String cityName) {
        this.cityName = cityName;
    }
}