package com.resolvos.customer;

import com.resolvos.common.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final TransactionRepository transactionRepository;

    public CustomerService(CustomerRepository customerRepository,
                           TransactionRepository transactionRepository) {
        this.customerRepository = customerRepository;
        this.transactionRepository = transactionRepository;
    }

    public List<CustomerRecord> search(String query) {
        List<Customer> list = (query == null || query.isBlank())
                ? customerRepository.findAllByOrderByNameAsc()
                : customerRepository.findByNameContainingIgnoreCaseOrderByNameAsc(query.trim());

        return list.stream()
                .map(CustomerRecord::from)
                .toList();
    }

    public CustomerRecord findById(UUID id) {
        return customerRepository.findById(id)
                .map(CustomerRecord::from)
                .orElseThrow(() -> ApiException.notFound("Customer not found: " + id));
    }

    public List<TransactionRecord> getTransactions(UUID customerId) {
        // Ensure customer exists in caller's tenant
        if (!customerRepository.existsById(customerId)) {
            throw ApiException.notFound("Customer not found: " + customerId);
        }

        return transactionRepository.findByCustomerIdOrderByOccurredAtDesc(customerId)
                .stream()
                .map(TransactionRecord::from)
                .toList();
    }

    public TransactionRecord getTransactionById(UUID transactionId) {
        return transactionRepository.findById(transactionId)
                .map(TransactionRecord::from)
                .orElseThrow(() -> ApiException.notFound("Transaction not found: " + transactionId));
    }
}
