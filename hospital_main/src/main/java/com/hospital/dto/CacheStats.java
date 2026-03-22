package com.hospital.dto;

public class CacheStats {

    private final long cacheSize;
    private final long hitCount;
    private final long missCount;
    private final double hitRate;

    public CacheStats(long cacheSize, long hitCount, long missCount) {
        this.cacheSize = cacheSize;
        this.hitCount  = hitCount;
        this.missCount = missCount;
        long total = hitCount + missCount;
        this.hitRate = total == 0 ? 0.0 : (double) hitCount / total * 100;
    }

    public long getCacheSize()   { return cacheSize; }
    public long getHitCount()    { return hitCount; }
    public long getMissCount()   { return missCount; }
    public double getHitRate()   { return hitRate; }
}