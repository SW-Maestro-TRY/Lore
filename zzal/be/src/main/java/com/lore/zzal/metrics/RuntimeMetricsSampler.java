package com.lore.zzal.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** One minute snapshots, bounded URI templates and no per-user/pet dimensions. No exporter endpoint. */
public final class RuntimeMetricsSampler {
    private final MeterRegistry registry;
    private final Supplier<Set<String>> routeTemplates;
    private final Supplier<Map<String,Double>> jvm;
    private final String instance=UUID.randomUUID().toString();
    private Instant previousAt;
    private final Map<String,Double> previousCounters=new HashMap<>();
    private Map<String,Sample> previousRequests=Map.of();
    private record Sample(long count,double millis,Map<Double,Long> buckets) {}
    private static final Set<String> METHODS=Set.of("GET","POST","PUT","PATCH","DELETE","OPTIONS","HEAD");
    public RuntimeMetricsSampler(MeterRegistry registry,Supplier<Set<String>> routeTemplates) {
        this(registry,routeTemplates,RuntimeMetricsSampler::jvmValues);
    }
    RuntimeMetricsSampler(MeterRegistry registry,Supplier<Set<String>> routeTemplates,Supplier<Map<String,Double>> jvm) {
        this.registry=registry;this.routeTemplates=routeTemplates;this.jvm=jvm;
    }
    public synchronized RuntimeSnapshot sample(Instant now,RuntimeSnapshot.Queue queue) {
        long interval=previousAt==null ? 0 : Math.max(0,java.time.Duration.between(previousAt,now).toMillis());
        previousAt=now;
        List<RuntimeSnapshot.Metric> metrics=new ArrayList<>();
        Map<String,Double> values=jvm.get();
        for(String key:List.of("jvm_heap_used_bytes","jvm_heap_committed_bytes","jvm_heap_max_bytes",
                "jvm_nonheap_used_bytes","jvm_nonheap_committed_bytes","jvm_thread_count","process_cpu_load")) {
            metrics.add(gauge(key,values.get(key),key.endsWith("bytes") ? "bytes" : key.endsWith("load") ? "ratio" : "count"));
        }
        for(String key:List.of("process_cpu_time_ms","jvm_gc_count","jvm_gc_time_ms"))
            metrics.add(counter(key,values.get(key),key.endsWith("ms") ? "ms" : "count"));
        for(var entry:Map.of("dbpool_active_connections","hikaricp.connections.active","dbpool_idle_connections","hikaricp.connections.idle",
                "dbpool_pending_threads","hikaricp.connections.pending","dbpool_max_connections","hikaricp.connections.max").entrySet()) {
            var gauges=registry.find(entry.getValue()).gauges();Double value=null;
            if(!gauges.isEmpty()) { double sum=0;boolean valid=true;for(var gauge:gauges) {double v=gauge.value();if(!Double.isFinite(v)||v<0){valid=false;break;}sum+=v;}if(valid)value=sum; }
            metrics.add(gauge(entry.getKey(),value,"count"));
        }
        // Only MVC's registered templates may be copied from registry tags. Raw URI-looking tags are rejected.
        Set<String> templates=routeTemplates.get();
        var allTimers=registry.find("http.server.requests").timers();
        List<Timer> timers=allTimers.stream().limit(4096).filter(t->{
            String uri=t.getId().getTag("uri"),method=t.getId().getTag("method"),status=t.getId().getTag("status");
            return uri!=null && uri.startsWith("/api/zzal/") && uri.length()<=200 && templates.contains(uri)
                    && method!=null && METHODS.contains(method) && status!=null && status.matches("[1-5][0-9]{2}");
        }).sorted(Comparator.comparing(t->t.getId().getTag("uri")+"|"+t.getId().getTag("method")+"|"+t.getId().getTag("status"))).toList();
        // Multiple timers with differing exception/outcome tags are aggregated into the same bounded series.
        Map<String,List<Timer>> grouped=new LinkedHashMap<>();int dropped=0;
        for(Timer timer:timers) {
            String key=timer.getId().getTag("uri")+"|"+timer.getId().getTag("method")+"|"+timer.getId().getTag("status");
            if(!grouped.containsKey(key)&&grouped.size()>=64){dropped++;continue;}
            grouped.computeIfAbsent(key,k->new ArrayList<>()).add(timer);
        }
        List<RuntimeSnapshot.Request> requests=new ArrayList<>();Map<String,Sample> current=new HashMap<>();
        for(var entry:grouped.entrySet()) {
            long count=0;double millis=0;Map<Double,Long> buckets=new TreeMap<>();boolean histogram=true;Set<Double> expected=null;
            for(Timer timer:entry.getValue()) {
                count+=timer.count();millis+=timer.totalTime(TimeUnit.MILLISECONDS);
                var snapshot=timer.takeSnapshot();Map<Double,Long> one=new TreeMap<>();
                for(var bucket:snapshot.histogramCounts()) if(Double.isFinite(bucket.bucket(TimeUnit.MILLISECONDS))) one.put(bucket.bucket(TimeUnit.MILLISECONDS),(long)bucket.count());
                if(one.isEmpty()||one.size()>32||expected!=null&&!expected.equals(one.keySet()))histogram=false;
                if(expected==null)expected=one.keySet();one.forEach((bound,n)->buckets.merge(bound,n,Long::sum));
            }
            if(!histogram)buckets.clear();
            Sample previous=previousRequests.get(entry.getKey()),sample=new Sample(count,millis,Map.copyOf(buckets));current.put(entry.getKey(),sample);
            String status=previous==null ? "baseline" : count<previous.count||millis<previous.millis ? "reset" : "ok";
            String histStatus=!histogram ? "missing" : previous==null||previous.buckets.isEmpty() ? "baseline" : !previous.buckets.keySet().equals(buckets.keySet()) ? "reset" : "available";
            if(histStatus.equals("available")) for(var b:buckets.entrySet()) if(b.getValue()<previous.buckets.get(b.getKey())) {histStatus="reset";break;}
            List<RuntimeSnapshot.Bucket> delta=new ArrayList<>();
            if(histStatus.equals("available")) buckets.forEach((bound,n)->delta.add(new RuntimeSnapshot.Bucket(bound,n-previous.buckets.get(bound))));
            String[] tags=entry.getKey().split("\\|");
            requests.add(new RuntimeSnapshot.Request("zzal",tags[0],tags[1],tags[2],status.equals("ok")?count-previous.count:null,
                    status.equals("ok")?millis-previous.millis:null,status,histStatus,delta,count,millis,buckets.entrySet().stream().map(b->new RuntimeSnapshot.TotalBucket(b.getKey(),b.getValue())).toList()));
        }
        previousRequests=current;
        return new RuntimeSnapshot("shared_api",now.toString(),interval,instance,metrics,requests,queue,dropped,allTimers.size()>4096||dropped>0 ? "truncated" : requests.isEmpty() ? "missing" : "ok");
    }
    private RuntimeSnapshot.Metric gauge(String key,Double value,String unit) {
        boolean valid=value!=null&&Double.isFinite(value)&&value>=0;
        return new RuntimeSnapshot.Metric("shared_api",key,valid?value:null,unit,"gauge",valid?"ok":"missing",null);
    }
    private RuntimeSnapshot.Metric counter(String key,Double value,String unit) {
        if(value==null||!Double.isFinite(value)||value<0) {previousCounters.remove(key);return new RuntimeSnapshot.Metric("shared_api",key,null,unit,"counter_delta","missing",null);}
        Double previous=previousCounters.put(key,value);String status=previous==null?"baseline":value<previous?"reset":"ok";
        return new RuntimeSnapshot.Metric("shared_api",key,status.equals("ok")?value-previous:null,unit,"counter_delta",status,value);
    }
    static Map<String,Double> jvmValues() {
        var values=new HashMap<String,Double>();var memory=ManagementFactory.getMemoryMXBean();
        values.put("jvm_heap_used_bytes",(double)memory.getHeapMemoryUsage().getUsed());
        values.put("jvm_heap_committed_bytes",(double)memory.getHeapMemoryUsage().getCommitted());
        values.put("jvm_heap_max_bytes",(double)memory.getHeapMemoryUsage().getMax());
        values.put("jvm_nonheap_used_bytes",(double)memory.getNonHeapMemoryUsage().getUsed());
        values.put("jvm_nonheap_committed_bytes",(double)memory.getNonHeapMemoryUsage().getCommitted());
        values.put("jvm_thread_count",(double)ManagementFactory.getThreadMXBean().getThreadCount());
        if(ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            values.put("process_cpu_load",os.getProcessCpuLoad());values.put("process_cpu_time_ms",os.getProcessCpuTime()<0?-1:os.getProcessCpuTime()/1_000_000.0);
        }
        long count=0,time=0;boolean known=true;for(var gc:ManagementFactory.getGarbageCollectorMXBeans()) {if(gc.getCollectionCount()<0||gc.getCollectionTime()<0){known=false;break;}count+=gc.getCollectionCount();time+=gc.getCollectionTime();}
        if(known){values.put("jvm_gc_count",(double)count);values.put("jvm_gc_time_ms",(double)time);}return values;
    }
}
