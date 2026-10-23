package lat.raczor.vehiclevault.tenant;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
class TenantConfig {

    /** Replaces Boot's default transaction manager so every transaction is tenant-bound. */
    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource, TenantContext tenants) {
        return new TenantBindingTransactionManager(dataSource, tenants);
    }
}
