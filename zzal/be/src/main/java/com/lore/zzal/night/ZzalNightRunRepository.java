package com.lore.zzal.night;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;

public interface ZzalNightRunRepository extends JpaRepository<ZzalNightRun, LocalDate> {
}
