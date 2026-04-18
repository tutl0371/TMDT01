package com.example.bizflow.repository;

import com.example.bizflow.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    @Query("""
        SELECT m FROM Message m
        WHERE (m.senderId = :userId AND m.receiverId = :ownerId)
           OR (m.senderId = :ownerId AND m.receiverId = :userId)
        ORDER BY m.createdAt ASC, m.id ASC
    """)
    List<Message> findThread(@Param("userId") Long userId, @Param("ownerId") Long ownerId);

    @Query("""
        SELECT m FROM Message m
        WHERE m.id IN (
            SELECT MAX(m2.id) FROM Message m2
            WHERE m2.senderId = :ownerId OR m2.receiverId = :ownerId
            GROUP BY CASE
                WHEN m2.senderId = :ownerId THEN m2.receiverId
                ELSE m2.senderId
            END
        )
        ORDER BY m.createdAt DESC, m.id DESC
    """)
    List<Message> findConversationLatestMessages(@Param("ownerId") Long ownerId);
}
