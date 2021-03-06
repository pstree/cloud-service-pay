package com.kunlun.api.service;

import com.alibaba.fastjson.JSON;
import com.kunlun.api.client.*;
import com.kunlun.entity.*;
import com.kunlun.enums.CommonEnum;
import com.kunlun.exception.PayException;
import com.kunlun.result.DataRet;
import com.kunlun.utils.*;
import com.kunlun.wxentity.UnifiedOrderNotifyRequestData;
import com.kunlun.wxentity.UnifiedOrderResponseData;
import com.kunlun.wxentity.UnifiedRequestData;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * @author by fk
 * @version <0.1>
 * @created on 2018-01-05.
 */
@Service
public class GroupServiceImpl implements GroupService {

    @Autowired
    private ActivityClient activityClient;

    @Autowired
    private DeliveryClient deliveryClient;

    @Autowired
    private GoodClient goodClient;

    @Autowired
    private OrderClient orderClient;

    @Autowired
    private LogClient logClient;

    /**
     * 参见拼团
     *
     * @param unifiedRequestData
     * @param ipAddress
     * @return
     */
    @Override
    public DataRet joinGroup(UnifiedRequestData unifiedRequestData, String ipAddress) {
//        String userId = WxUtil.getOpenId(unifiedRequestData.getWxCode());

        //查询是否参见过活动
        DataRet activityRet = activityClient.checkActivity(unifiedRequestData.getGoodId(), unifiedRequestData.getActivityId(), unifiedRequestData.getWxCode());
        if (!activityRet.isSuccess()) {
            return new DataRet("ERROR", activityRet.getMessage());
        }
        //校验活动商品是否还有库存
        DataRet<String> activityGoodRet = activityClient.checkActivityGood(unifiedRequestData.getGoodId());
        if (!activityGoodRet.isSuccess()) {
            return new DataRet("ERROR", activityGoodRet.getMessage());
        }
        //获取收货地址
        DataRet<Delivery> deliveryRet = deliveryClient.findById(unifiedRequestData.getDeliveryId());
        if (!deliveryRet.isSuccess()) {
            return new DataRet<>("ERROR", deliveryRet.getMessage());
        }
        //获取商品信息
        DataRet<Good> goodDataRet = goodClient.findById(unifiedRequestData.getGoodId());
        if (!goodDataRet.isSuccess()) {
            return new DataRet("ERROR", goodDataRet.getMessage());
        }
        //组装商品快照
        GoodSnapshot goodSnapshot = CommonUtil.snapshotConstructor(goodDataRet.getBody(), unifiedRequestData.getGoodId());
        DataRet<String> goodSnapshotDataRet = goodClient.addGoodSnapShot(goodSnapshot);
        if (!goodSnapshotDataRet.isSuccess()) {
            return new DataRet("ERROR", goodSnapshotDataRet.getMessage());
        }
        //构建订单
        Order order = orderConstructor(goodDataRet.getBody(), goodSnapshot.getId(), deliveryRet.getBody(), unifiedRequestData, unifiedRequestData.getWxCode());
        DataRet<String> orderDataRet = orderClient.addOrder(order);
        if (!orderDataRet.isSuccess()) {
            return new DataRet("ERROR", orderDataRet.getMessage());
        }

        //创建订单日志
        OrderLog orderLog = CommonUtil.constructOrderLog(order.getOrderNo(), "生成预付款订单", ipAddress, order.getId());
        DataRet<String> orderLogRet = logClient.addOrderLog(orderLog);
        if (!orderLogRet.isSuccess()) {
            return new DataRet("ERROR", orderLogRet.getMessage());
        }

        //微信统一下单
        String nonce_str = WxUtil.createRandom(false, 10);
        String unifiedOrderXML = WxSignUtil.unifiedOrder(goodDataRet.getBody().getGoodName(),
                unifiedRequestData.getWxCode(), order.getOrderNo(), order.getPaymentFee(),
                nonce_str, "GROUP");
        //调用微信统一下单接口,生成预付款订单
        String wxOrderResponse = WxUtil.httpsRequest(WxPayConstant.WECHAT_UNIFIED_ORDER_URL, "POST", unifiedOrderXML);
        //将xml返回信息转换成bean
        UnifiedOrderResponseData unifiedOrderResponseData = XmlUtil.castXMLStringToUnifiedOrderResponseData(wxOrderResponse);

        if ("FAIL".equalsIgnoreCase(unifiedOrderResponseData.getReturn_code())) {
            //扔出微信下单错误
            throw new PayException(unifiedOrderResponseData.getReturn_msg());
        }

        //修改订单预付款订单号
        DataRet<String> prepayIdRet = orderClient.updateOrderPrepayId(order.getId(), unifiedOrderResponseData.getPrepay_id());
        if (!prepayIdRet.isSuccess()) {
            return new DataRet<>("ERROR", "修改预付款订单号失败");
        }

        //时间戳
        Long timeStamp = System.currentTimeMillis() / 1000;

        //随机字符串
        String nonceStr = WxSignUtil.createRandom(false, 10);

        //生成支付签名
        Map<String, Object> map = WxSignUtil.payParam(timeStamp, nonceStr, unifiedOrderResponseData.getPrepay_id());
        String paySign = WxSignUtil.paySign(map);
        map.put("paySign", paySign);
        return new DataRet<>(JSON.toJSON(map));
//        return new DataRet<>(JacksonUtil.toJSON(map));
    }

    /**
     * 拼团回调
     *
     * @param unifiedOrderNotifyRequestData
     * @return
     */
    @Override
    public DataRet callBack(UnifiedOrderNotifyRequestData unifiedOrderNotifyRequestData) {
        DataRet orderRet = orderClient.findOrderByOrderNo(unifiedOrderNotifyRequestData.getOut_trade_no());
        if (!orderRet.isSuccess()) {
            return new DataRet("ERROR", orderRet.getMessage());
        }
        Order order = (Order) orderRet.getBody();
        //判断是否成功接收回调
        if (order.getPayDate() == null && CommonEnum.UN_PAY.getCode().equals(order.getOrderStatus())) {
            //修改订单状态与微信支付订单号
            DataRet<String> orderModifyRet = orderClient.modifyStatusAndPayOrderNo(order.getId(),
                    CommonEnum.UN_DELIVERY.getCode(), unifiedOrderNotifyRequestData.getTransaction_id());
            if (!orderModifyRet.isSuccess()) {
                return new DataRet<>("ERROR", orderModifyRet.getMessage());
            }
            //修改订单日志
            OrderLog orderLog = CommonUtil.constructOrderLog(order.getOrderNo(), "付款", "", order.getId());
            DataRet<String> orderLogRet = logClient.addOrderLog(orderLog);
            if (!orderLogRet.isSuccess()) {
                return new DataRet<>("ERROR", orderLogRet.getMessage());
            }
            //获取活动商品
            DataRet<ActivityGood> activityGoodDataRet = activityClient.findActivityGoodStock(order.getGoodId());
            if (!activityGoodDataRet.isSuccess()) {
                return new DataRet<>("ERROR", activityGoodDataRet.getMessage());
            }
            ActivityGood activityGood = activityGoodDataRet.getBody();
            //活动商品库存扣减
            DataRet<String> updateStockDataRet = activityClient.updateStock(activityGood.getId(), order.getCount());
            if (!updateStockDataRet.isSuccess()) {
                return new DataRet<>("ERROR", updateStockDataRet.getMessage());
            }
            //创建商品库存扣减日志
            GoodLog goodLog = CommonUtil.constructGoodLog(order.getGoodId(), order.getGoodName(), "库存扣减" + order.getCount());
            DataRet<String> goodLogRet = logClient.addGoodLog(goodLog);
            if (!goodLogRet.isSuccess()) {
                return new DataRet<>("ERROR", goodLogRet.getMessage());
            }
            //销量
            DataRet<String> saleVolumeDataRet = goodClient.updateSaleVolume(order.getCount(), order.getGoodId());
            if (!saleVolumeDataRet.isSuccess()) {
                return new DataRet<>("ERROR", saleVolumeDataRet.getMessage());
            }
        }
        return new DataRet("支付成功回调");
    }

    /**
     * 构建订单
     *
     * @param good
     * @param goodSnapshotId
     * @param delivery
     * @param unifiedRequestData
     * @param userId
     * @return
     */
    private Order orderConstructor(Good good, Long goodSnapshotId, Delivery delivery, UnifiedRequestData unifiedRequestData, String userId) {
        Order order = new Order();
        BeanUtils.copyProperties(delivery, order);
        String orderNo = OrderNoUtil.getOrderNo();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setSellerId(good.getSellerId());
        order.setPayType(CommonEnum.WE_CHAT_PAY.getCode());
        order.setOrderType(CommonEnum.SPELL_GROUP.getCode());
        order.setOrderStatus(CommonEnum.UN_PAY.getCode());
        order.setGoodName(good.getGoodName());
        order.setGoodSnapshotId(goodSnapshotId);
        order.setGoodId(good.getId());
        order.setGoodName(good.getGoodName());
        order.setGoodFee(good.getPrice());
        order.setUsePoint(CommonEnum.NOT_USE_POINT.getCode());
        order.setUseTicket(CommonEnum.UN_USE_TICKET.getCode());
        order.setFreightFee(unifiedRequestData.getFreightFee());
        order.setPaymentFee(unifiedRequestData.getPaymentFee());
        order.setDeliveryId(unifiedRequestData.getDeliveryId());
        order.setOrderFee(unifiedRequestData.getOrderFee());
        order.setCount(1);
        order.setSellerId(good.getSellerId());
        return order;
    }
}
