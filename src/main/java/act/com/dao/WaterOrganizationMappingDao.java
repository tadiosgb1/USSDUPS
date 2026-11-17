package act.com.dao;

import act.com.models.WaterOrganizationMapping;

import javax.ejb.Stateless;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceContext;

@Stateless
public class WaterOrganizationMappingDao {
    @PersistenceContext(unitName = "primary")
    private EntityManager em;

    public WaterOrganizationMapping findByWegCode(String wegCode) {
        try {
            return em.createQuery(
                            "SELECT w FROM WaterOrganizationMapping w WHERE w.wegCode = :wegCode",
                            WaterOrganizationMapping.class)
                    .setParameter("wegCode", wegCode)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }
}
