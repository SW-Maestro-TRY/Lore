package com.lore.webtoon.series;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AttendRemindRepository extends JpaRepository<AttendRemind, Long> {

    /** 아직 알림을 보내는 중인 줄. */
    List<AttendRemind> findByDoneAtIsNull();
}
