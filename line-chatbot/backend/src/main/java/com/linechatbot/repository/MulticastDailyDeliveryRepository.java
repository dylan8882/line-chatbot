package com.linechatbot.repository;

import com.linechatbot.model.entity.MulticastDailyDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

@Repository
public interface MulticastDailyDeliveryRepository extends JpaRepository<MulticastDailyDelivery, LocalDate> {
}
