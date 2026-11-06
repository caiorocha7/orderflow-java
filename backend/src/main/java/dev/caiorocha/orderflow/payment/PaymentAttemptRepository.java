package dev.caiorocha.orderflow.payment;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {

    int countByOrderId(Long orderId);

    List<PaymentAttempt> findByOrderIdOrderByAttemptAsc(Long orderId);
}
