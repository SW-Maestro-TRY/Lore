package com.lore.webtoon.series;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SeriesDraftRepository extends JpaRepository<SeriesDraft, String> {
    java.util.List<SeriesDraft> findByOwnerId(Long ownerId);
}
