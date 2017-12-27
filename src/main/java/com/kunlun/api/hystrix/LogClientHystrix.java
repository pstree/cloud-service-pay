package com.kunlun.api.hystrix;

import com.kunlun.api.client.LogClient;
import com.kunlun.entity.OrderLog;
import com.kunlun.result.DataRet;
import org.springframework.stereotype.Component;

/**
 * @author by hws
 * @created on 2017/12/26.
 */
@Component
public class LogClientHystrix implements LogClient{

    /**
     * 创建订单日志
     *
     * @param orderLog
     * @return
     */
    @Override
    public DataRet<String> addOrderLog(OrderLog orderLog) {
        return new DataRet<>("ERROR","创建订单日志异常");
    }
}
