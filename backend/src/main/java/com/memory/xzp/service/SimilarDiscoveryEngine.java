package com.memory.xzp.service;

import com.memory.xzp.model.dto.FileFeatureQueryDTO;
import com.memory.xzp.utils.CosineSimilarityUtil;
import java.util.*;
import java.util.function.IntConsumer;

/** Exact cosine components with normalized vectors, duplicate hashing and cooperative cancellation. */
public final class SimilarDiscoveryEngine {
    private SimilarDiscoveryEngine() {}
    public static List<List<Integer>> group(List<FileFeatureQueryDTO> features, double threshold,
            long deadlineNanos, IntConsumer progress) {
        int n = features.size();
        int[] parent = new int[n];
        float[][] vectors = new float[n][];
        Map<VectorKey,Integer> identical = new HashMap<>();
        List<Integer> representatives = new ArrayList<>();
        for (int i=0; i<n; i++) {
            check(deadlineNanos); parent[i]=i;
            var feature=features.get(i); byte[] bytes=feature.getFeatureVector();
            Integer dim=feature.getFeatureDim();
            if (bytes==null || dim==null || dim<1 || dim>4096 || bytes.length!=dim*4) continue;
            float[] vector=CosineSimilarityUtil.bytesToFloats(bytes);
            double squared=0;
            for (float v:vector) squared+=(double)v*v;
            if (!Double.isFinite(squared) || squared<=0) continue;
            double norm=Math.sqrt(squared);
            for(int j=0;j<vector.length;j++) vector[j]/=(float)norm;
            vectors[i]=vector;
            Integer duplicate=identical.putIfAbsent(new VectorKey(bytes),i);
            if(duplicate!=null) parent[i]=duplicate;
            else representatives.add(i);
        }
        int total=representatives.size();
        for(int a=0;a<total;a++) {
            check(deadlineNanos);
            int i=representatives.get(a);
            for(int b=a+1;b<total;b++) {
                if ((b & 255)==0) check(deadlineNanos);
                int j=representatives.get(b);
                if(vectors[i].length!=vectors[j].length || root(parent,i)==root(parent,j)) continue;
                double dot=0;
                for(int d=0;d<vectors[i].length;d++) dot+=(double)vectors[i][d]*vectors[j][d];
                if(dot+1e-7>=threshold) parent[root(parent,j)]=root(parent,i);
            }
            progress.accept((a+1)*100/Math.max(1,total));
        }
        Map<Integer,List<Integer>> groups=new LinkedHashMap<>();
        for(int i=0;i<n;i++) if(vectors[i]!=null) groups.computeIfAbsent(root(parent,i),k->new ArrayList<>()).add(i);
        progress.accept(100);
        return groups.values().stream().filter(g->g.size()>1).toList();
    }
    private static void check(long deadline) {
        if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("相似发现已取消");
        if(System.nanoTime()>deadline) throw new IllegalStateException("相似发现超时，请缩小图库范围后重试");
    }
    private static int root(int[] p,int i) {
        while(p[i]!=i) {p[i]=p[p[i]];i=p[i];} return i;
    }
    private record VectorKey(byte[] bytes) {
        @Override public int hashCode(){return Arrays.hashCode(bytes);}
        @Override public boolean equals(Object o){return o instanceof VectorKey k && Arrays.equals(bytes,k.bytes);}
    }
}
