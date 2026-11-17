package act.com.dao;

import act.com.models.CustomerInfoView;
import javax.ejb.Stateless;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.TypedQuery;
import java.util.List;
@Stateless
public class CustomerInfoViewDao {

    @PersistenceContext(unitName = "primary")
    private EntityManager em;

    public List<CustomerInfoView> getAccounts(final String phone) {
        final TypedQuery<CustomerInfoView> accounts = (TypedQuery<CustomerInfoView>)this.em.createQuery("SELECT DISTINCT c FROM CustomerInfoView c where c.phone=:phone", (Class)CustomerInfoView.class).setParameter("phone", (Object)phone);
        return accounts.getResultList();
    }


    public CustomerInfoView getBranch(final String account) {
        final TypedQuery<CustomerInfoView> accounts = (TypedQuery<CustomerInfoView>)this.em.createQuery("SELECT DISTINCT c FROM CustomerInfoView c where c.account=:account", (Class)CustomerInfoView.class).setParameter("account", (Object)account);
        return accounts.getSingleResult();
    }

}

