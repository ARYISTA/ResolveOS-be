package com.resolvos.ticket;

import com.resolvos.customer.Customer;
import com.resolvos.customer.CustomerRepository;
import com.resolvos.customer.Transaction;
import com.resolvos.customer.TransactionRepository;
import com.resolvos.refund.RefundRepository;
import com.resolvos.tenant.TenantSettings;
import com.resolvos.tenant.TenantSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class CaseValidationService {

    private final CustomerRepository customerRepository;
    private final TransactionRepository transactionRepository;
    private final RefundRepository refundRepository;
    private final TenantSettingsRepository settingsRepository;

    public record ValidationRuleResult(String rule, boolean passed, String reason) {}
    public record ValidationResult(
            boolean allPassed,
            List<ValidationRuleResult> checks,
            String message,
            Customer customer,
            Transaction transaction,
            TenantSettings settings
    ) {}

    public CaseValidationService(CustomerRepository customerRepository,
                                 TransactionRepository transactionRepository,
                                 RefundRepository refundRepository,
                                 TenantSettingsRepository settingsRepository) {
        this.customerRepository = customerRepository;
        this.transactionRepository = transactionRepository;
        this.refundRepository = refundRepository;
        this.settingsRepository = settingsRepository;
    }

    public ValidationResult validate(UUID tenantId, UUID customerId, UUID transactionId, BigDecimal requestedAmount) {
        List<ValidationRuleResult> checks = new ArrayList<>(5);

        // 1. Customer exists in tenant
        Optional<Customer> optCustomer = customerRepository.findById(customerId);
        boolean customerValid = optCustomer.isPresent() && optCustomer.get().getTenantId().equals(tenantId);
        if (customerValid) {
            checks.add(new ValidationRuleResult("CUSTOMER_EXISTS", true, "Customer verified for tenant"));
        } else {
            checks.add(new ValidationRuleResult("CUSTOMER_EXISTS", false, "Customer does not exist in caller tenant"));
        }

        // 2. Transaction exists and belongs to customer
        Optional<Transaction> optTxn = transactionRepository.findById(transactionId);
        boolean txnExists = optTxn.isPresent() && optTxn.get().getTenantId().equals(tenantId);
        boolean txnOwner = txnExists && optTxn.get().getCustomerId().equals(customerId);
        if (txnOwner) {
            checks.add(new ValidationRuleResult("TRANSACTION_EXISTS", true, "Transaction verified and belongs to customer"));
        } else {
            checks.add(new ValidationRuleResult("TRANSACTION_EXISTS", false,
                    !txnExists ? "Transaction not found for tenant" : "Transaction does not belong to customer"));
        }

        // Fetch settings (or fallback default)
        TenantSettings settings = settingsRepository.findById(tenantId)
                .orElse(new TenantSettings(tenantId, new BigDecimal("5000.00"), new BigDecimal("25000.00"),
                        new BigDecimal("100000.00"), new BigDecimal("0.700")));

        // 3. Transaction window
        boolean withinWindow = false;
        if (txnExists) {
            Instant txnTime = optTxn.get().getOccurredAt();
            long daysOld = ChronoUnit.DAYS.between(txnTime, Instant.now());
            withinWindow = daysOld <= settings.getRefundWindowDays();
            if (withinWindow) {
                checks.add(new ValidationRuleResult("TRANSACTION_WINDOW", true,
                        "Transaction is " + daysOld + " days old, within " + settings.getRefundWindowDays() + "-day window"));
            } else {
                checks.add(new ValidationRuleResult("TRANSACTION_WINDOW", false,
                        "Transaction occurred " + daysOld + " days ago, exceeding " + settings.getRefundWindowDays() + "-day refund window"));
            }
        } else {
            checks.add(new ValidationRuleResult("TRANSACTION_WINDOW", false, "Cannot verify window for absent transaction"));
        }

        // 4. Duplicate check
        boolean duplicate = refundRepository.existsByTicketId(transactionId)
                || (txnExists && "Refunded".equalsIgnoreCase(optTxn.get().getStatus()));
        if (!duplicate) {
            checks.add(new ValidationRuleResult("DUPLICATE_CHECK", true, "No existing refund found for transaction"));
        } else {
            checks.add(new ValidationRuleResult("DUPLICATE_CHECK", false, "Transaction has already been refunded"));
        }

        // 5. Amount limit
        boolean amountOk = txnExists && requestedAmount != null
                && requestedAmount.compareTo(BigDecimal.ZERO) > 0
                && requestedAmount.compareTo(optTxn.get().getAmount()) <= 0;
        if (amountOk) {
            checks.add(new ValidationRuleResult("AMOUNT_LIMIT", true,
                    "Requested amount ₹" + requestedAmount + " does not exceed transaction amount ₹" + optTxn.get().getAmount()));
        } else {
            checks.add(new ValidationRuleResult("AMOUNT_LIMIT", false,
                    !txnExists ? "Cannot check amount: transaction not found"
                            : "Requested amount ₹" + requestedAmount + " exceeds transaction amount ₹" + optTxn.get().getAmount()));
        }

        boolean allPassed = checks.stream().allMatch(ValidationRuleResult::passed);
        String message = allPassed
                ? "Validation Agent: all 5 core integrity checks passed"
                : "Validation Agent: 1 or more integrity checks failed";

        return new ValidationResult(
                allPassed,
                checks,
                message,
                optCustomer.orElse(null),
                optTxn.orElse(null),
                settings
        );
    }
}
