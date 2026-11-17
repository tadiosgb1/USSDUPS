package act.com.dao;

import act.com.models.PaymentCodeValidationLog;

import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.util.logging.Level;
import java.util.logging.Logger;

@Stateless
public class PaymentCodeValidationLogDao {

    private static final Logger LOGGER = Logger.getLogger(PaymentCodeValidationLogDao.class.getName());

    @PersistenceContext(unitName = "primary")
    private EntityManager em;

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void save(PaymentCodeValidationLog log) {
        try {
            em.persist(log);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to persist PaymentCodeValidationLog for session " + log.getSessionId(), e);
        }
    }
}