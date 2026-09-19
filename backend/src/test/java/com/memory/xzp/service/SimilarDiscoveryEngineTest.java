package com.memory.xzp.service;

import com.memory.xzp.model.dto.FileFeatureQueryDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SimilarDiscoveryEngineTest {
    private FileFeatureQueryDTO vector(float... values) {
        FileFeatureQueryDTO result=new FileFeatureQueryDTO();
        ByteBuffer buffer=ByteBuffer.allocate(values.length*4).order(ByteOrder.LITTLE_ENDIAN);
        for(float value:values)buffer.putFloat(value);
        result.setFeatureDim(values.length);result.setFeatureVector(buffer.array());return result;
    }
    @Test void findsExactAndCloseVectorsWhileRejectingInvalidFeatures() {
        var result=SimilarDiscoveryEngine.group(List.of(vector(1,0),vector(1,0),vector(.99f,.01f),vector(0,1),vector(Float.NaN,1),vector(0,0)),
                .99,System.nanoTime()+TimeUnit.SECONDS.toNanos(2),p->{});
        assertEquals(List.of(List.of(0,1,2)),result);
    }
    @Test void interruptedAndExpiredWorkNeverReturnsPartialSuccess() {
        assertThrows(IllegalStateException.class,()->SimilarDiscoveryEngine.group(List.of(vector(1,0)),.9,0,p->{}));
        Thread.currentThread().interrupt();
        try {assertThrows(CancellationException.class,()->SimilarDiscoveryEngine.group(List.of(vector(1,0)),.9,Long.MAX_VALUE,p->{}));}
        finally{Thread.interrupted();}
    }
    @Test
    @EnabledIfSystemProperty(named="agent.performance",matches="true")
    void benchmarkTenThousand1024DimensionalImages() throws Exception {
        Random random=new Random(913);
        List<FileFeatureQueryDTO> features=new ArrayList<>();
        for(int i=0;i<10000;i++) {
            if(i>=9900) {features.add(features.get(i-9900));continue;}
            float[] values=new float[1024];
            for(int d=0;d<values.length;d++)values[d]=(float)random.nextGaussian();
            features.add(vector(values));
        }
        long started=System.nanoTime();
        var groups=SimilarDiscoveryEngine.group(features,.95,started+TimeUnit.SECONDS.toNanos(120),p->{});
        long millis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
        assertEquals(100,groups.size());
        assertTrue(groups.stream().allMatch(g->g.size()==2));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/similar-performance.json"),
                "{\"images\":10000,\"dimensions\":1024,\"knownDuplicatePairs\":100,\"foundPairs\":"+groups.size()+",\"elapsedMs\":"+millis+",\"modelCalls\":0,\"seed\":913}");
    }
}
