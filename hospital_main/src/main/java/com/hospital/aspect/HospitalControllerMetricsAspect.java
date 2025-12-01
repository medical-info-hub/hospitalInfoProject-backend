package com.hospital.aspect;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Aspect
@Component
public class HospitalControllerMetricsAspect {

    private final MeterRegistry meterRegistry;

    public HospitalControllerMetricsAspect(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    // HospitalWebController의 모든 public 메소드 대상
    @Around("execution(* com.hospital.controller.HospitalWebController.*(..))")
    public Object measureControllerMetrics(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().getName();
        long start = System.currentTimeMillis();
        String status = "success";

        try {
            return joinPoint.proceed();
        } catch (Throwable e) {
            status = "error";
            // 에러 발생 카운터 (Exception 타입별)
            meterRegistry.counter("hospital.api.errors", 
                "method", methodName, 
                "exception", e.getClass().getSimpleName()
            ).increment();
            throw e;
        } finally {
            long duration = System.currentTimeMillis() - start;
            
            // 실행 시간 및 요청 수 기록 (HTTP Statistics)
            // Timer는 count(요청 수), total_time(총 소요시간), max(최대 소요시간)를 제공함
            Timer.builder("hospital.api.http.requests")
                    .description("Execution time of HospitalWebController endpoints")
                    .tag("method", methodName)
                    .tag("status", status)
                    .register(meterRegistry)
                    .record(duration, TimeUnit.MILLISECONDS);
        }
    }
}
