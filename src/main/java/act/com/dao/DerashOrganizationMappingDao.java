package act.com.dao;

import act.com.models.DerashOrganizationMapping;

import javax.ejb.Stateless;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;

@Stateless
public class DerashOrganizationMappingDao {

    @PersistenceContext(unitName = "primary")
    private EntityManager em;

    public DerashOrganizationMapping findByCode(String code) {
        try {
            Query q = em.createNamedQuery("DerashOrganizationMapping.findByCode", DerashOrganizationMapping.class);
            q.setParameter("code", code);
            return (DerashOrganizationMapping) q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }
}