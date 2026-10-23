package com.lore.webtoon.community;

/** 구독은 작품(root job), 댓글은 회차(run)에 속한다. */
public interface SeriesIdentity {
    record Identity(String rootJob, int episode) {}
    Identity of(String jobId);
}
