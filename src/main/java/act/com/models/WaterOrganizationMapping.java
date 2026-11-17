package act.com.models;

import javax.persistence.*;
import java.io.Serializable;

@Entity
@Table(name = "water_organization_mapping")
public class WaterOrganizationMapping {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "city_name", nullable = false)
    private String cityName;

    @Column(name = "weg_code", nullable = false, unique = true, length = 3)
    private String wegCode;

    @Column(name = "organization_code", nullable = false)
    private String organizationCode;

    @Column(name = "organization_name", nullable = false)
    private String organizationName;

    public WaterOrganizationMapping() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCityName() {
        return cityName;
    }

    public void setCityName(String cityName) {
        this.cityName = cityName;
    }

    public String getWegCode() {
        return wegCode;
    }

    public void setWegCode(String wegCode) {
        this.wegCode = wegCode;
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
}
