package com.lore.zzal.metrics;

import java.util.List;

/** Private telemetry schema. Shared JVM resources are never attributed exclusively to zzal. */
public record RuntimeSnapshot(String scope, String observed_at, long interval_ms, String instance_id,
                              List<Metric> metrics, List<Request> requests, Queue queue,
                              int dropped_request_series,String requests_status) {
    public RuntimeSnapshot {
        if(!"shared_api".equals(scope) || metrics.size()>32 || requests.size()>64)
            throw new IllegalArgumentException("bounded runtime snapshot required");
        metrics=List.copyOf(metrics); requests=List.copyOf(requests);
    }
    public record Metric(String scope,String key,Double value,String unit,String kind,String status,Double raw_total) {}
    public record Bucket(double le_ms,Long count_delta) {}
    public record TotalBucket(double le_ms,long count_total) {}
    public record Request(String scope,String uri,String method,String status,Long count_delta,
                          Double total_time_ms_delta,String counter_status,String histogram_status,List<Bucket> histogram,
                          long count_total,double total_time_ms_total,List<TotalBucket> histogram_total) {
        public Request { if(histogram.size()>32 || histogram_total.size()>32) throw new IllegalArgumentException("histogram limit"); histogram=List.copyOf(histogram); histogram_total=List.copyOf(histogram_total); }
    }
    public record Queue(String scope,Integer queued_jobs,Integer running_jobs,String status,String source_table,String queue_kind) {
        public Queue(String scope,Integer queued,Integer running,String status) {
            this(scope,queued,running,status,"zzal_gen_job","GENERATION_V1");
        }
    }
}
