package com.lore.common.s3;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UploadTicketRepository extends JpaRepository<UploadTicket, Long> {

    Optional<UploadTicket> findByS3Key(String s3Key);
}
