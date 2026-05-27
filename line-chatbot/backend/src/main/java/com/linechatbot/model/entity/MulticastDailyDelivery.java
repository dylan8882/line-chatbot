package com.linechatbot.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 「LINE 平台 multicast 當日累計送達數」的本地快取。
 *
 * <p>每次 scheduler 結算 multicast task 時：先撈 LINE API 拿當日 total，
 * 算 delta = total - last_total 寫進 task，再把 last_total 更新成 total。
 * 同一天多個 task 共享同一份 last_total（增量切片給每個 task）。
 */
@Entity
@Table(name = "multicast_daily_delivery")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MulticastDailyDelivery {

    @Id
    @Column(nullable = false)
    private LocalDate date;

    @Column(name = "last_total", nullable = false)
    @Builder.Default
    private Long lastTotal = 0L;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
