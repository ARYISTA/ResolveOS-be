package com.resolvos.review;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface HumanReviewRepository extends JpaRepository<HumanReview, UUID> {
    Optional<HumanReview> findByTicketId(UUID ticketId);
}
