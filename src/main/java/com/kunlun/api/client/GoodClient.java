package com.kunlun.api.client;

import com.kunlun.api.hystrix.GoodClientHystrix;
import com.kunlun.entity.Good;
import com.kunlun.entity.GoodSnapshot;
import com.kunlun.result.DataRet;
import org.springframework.cloud.netflix.feign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * @author by kunlun
 * @version <0.1>
 * @created on 2017/12/26.
 */
@FeignClient(value = "cloud-service-good",fallback = GoodClientHystrix.class)
public interface GoodClient {

    /**
     * 商品信息检查
     * @param goodId
     * @return
     */
    @GetMapping("/checkGood")
    DataRet<String> checkGoodById(@RequestParam("goodId")Long goodId,
                                  @RequestParam("count") Integer count,
                                  @RequestParam("orederFee") Integer orderFee);


    /**
     * 根据id查找详情
     * @param id
     * @return
     */
    @GetMapping("/findById")
    DataRet<Good> findById(@RequestParam(value = "id") Long id);

    /**
     * 新增商品快照
     * @param goodSnapshot
     * @return
     */
    @PostMapping("/addGoodSnapShot")
    DataRet<String> addGoodSnapShot(@RequestBody GoodSnapshot goodSnapshot);

}