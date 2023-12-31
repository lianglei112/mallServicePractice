package com.macro.mall.portal.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.github.pagehelper.PageHelper;
import com.macro.mall.common.api.CommonPage;
import com.macro.mall.common.exception.Asserts;
import com.macro.mall.common.service.RedisService;
import com.macro.mall.mapper.*;
import com.macro.mall.model.*;
import com.macro.mall.portal.component.CancelOrderSender;
import com.macro.mall.portal.dao.OmsOrderItemDao;
import com.macro.mall.portal.dao.PortalOrderDao;
import com.macro.mall.portal.domain.*;
import com.macro.mall.portal.service.*;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author lianglei
 * @version 1.0
 * @date 2023/7/5 22:30
 * @deprecated 订单管理service层实现类
 */
@Slf4j
@Service
public class OmsPortalOrderServiceImpl implements OmsPortalOrderService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OmsPortalOrderServiceImpl.class);

    @Autowired
    private OmsCartItemService omsCartItemService;

    @Autowired
    private UmsMemberService umsMemberService;

    @Autowired
    private UmsMemberReceiveAddressService umsMemberReceiveAddressService;

    @Autowired
    private UmsMemberCouponService umsMemberCouponService;

    @Autowired
    private UmsIntegrationConsumeSettingMapper umsIntegrationConsumeSettingMapper;

    @Autowired
    private PmsSkuStockMapper pmsSkuStockMapper;

    @Autowired
    private RedisService redisService;

    @Value("${redis.key.orderId}")
    private String REDIS_KEY_ORDER_ID;

    @Value("${redis.database}")
    private String REDIS_DATABASE;

    @Autowired
    private OmsOrderSettingMapper omsOrderSettingMapper;

    @Autowired
    private OmsOrderMapper omsOrderMapper;

    @Autowired
    private OmsOrderItemMapper omsOrderItemMapper;

    @Autowired
    private OmsOrderItemDao omsOrderItemDao;

    @Autowired
    private SmsCouponHistoryMapper smsCouponHistoryMapper;

    @Autowired
    private CancelOrderSender cancelOrderSender;

    @Autowired
    private PortalOrderDao portalOrderDao;


    //TODO 优惠券相关代码需要重新编写
    @Override
    public ConfirmOrderResult generateConfirmOrder(List<Long> cartIds) {
        ConfirmOrderResult result = new ConfirmOrderResult();
        // 1、获取购物车信息
        UmsMember currentMember = umsMemberService.getCurrentMember();
        List<CartPromotionItem> cartPromotionItemList = omsCartItemService.listPromotion(currentMember.getId(), cartIds);
        result.setCartPromotionItemList(cartPromotionItemList);
        //2、获取用户收货地址列表
        List<UmsMemberReceiveAddress> memberReceiveAddressList = umsMemberReceiveAddressService.list();
        result.setMemberReceiveAddressList(memberReceiveAddressList);
        //3、获取用户可用优惠券列表
        List<SmsCouponHistoryDetail> couponHistoryDetailList = umsMemberCouponService.listCart(cartPromotionItemList, 1);
        result.setCouponHistoryDetailList(couponHistoryDetailList);
        //4、获取用户积分
        result.setMemberIntegration(currentMember.getIntegration());
        //5、获取积分使用规则
        UmsIntegrationConsumeSetting integrationConsumeSetting = umsIntegrationConsumeSettingMapper.selectByPrimaryKey(1L);
        result.setIntegrationConsumeSetting(integrationConsumeSetting);
        //6、计算总金额、活动优惠、应付金额
        ConfirmOrderResult.CalcAmount calcAmount = calcCartAmount(cartPromotionItemList);
        result.setCalcAmount(calcAmount);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.DEFAULT, propagation = Propagation.REQUIRED)
    public Map<String, Object> generateOrder(OrderParam orderParam) {
        List<OmsOrderItem> orderItemList = new ArrayList<>();
        //1、获取购物车信息及优惠信息
        UmsMember currentMember = umsMemberService.getCurrentMember();
        List<CartPromotionItem> cartPromotionItemList = omsCartItemService.listPromotion(currentMember.getId(), orderParam.getCartIds());
        for (CartPromotionItem cartPromotionItem : cartPromotionItemList) {
            //2、生成下单商品信息
            OmsOrderItem omsOrderItem = new OmsOrderItem();
            omsOrderItem.setProductId(cartPromotionItem.getProductId());
            omsOrderItem.setProductName(cartPromotionItem.getProductName());
            omsOrderItem.setProductPic(cartPromotionItem.getProductPic());
            omsOrderItem.setProductAttr(cartPromotionItem.getProductAttr());
            omsOrderItem.setProductBrand(cartPromotionItem.getProductBrand());
            omsOrderItem.setProductSn(cartPromotionItem.getProductSn());
            omsOrderItem.setProductPrice(cartPromotionItem.getPrice());
            omsOrderItem.setProductQuantity(cartPromotionItem.getQuantity());
            omsOrderItem.setProductSkuId(cartPromotionItem.getProductSkuId());
            omsOrderItem.setProductSkuCode(cartPromotionItem.getProductSkuCode());
            omsOrderItem.setProductCategoryId(cartPromotionItem.getProductCategoryId());
            omsOrderItem.setPromotionAmount(cartPromotionItem.getReduceAmount());
            omsOrderItem.setPromotionName(cartPromotionItem.getPromotionMessage());
            omsOrderItem.setGiftIntegration(cartPromotionItem.getIntegration());
            omsOrderItem.setGiftGrowth(cartPromotionItem.getGrowth());
            orderItemList.add(omsOrderItem);
        }
        //3、判断购物车中商品是否都有库存
        if (!hasStock(cartPromotionItemList)) {
            Asserts.fail("库存不足，无法下单！");
        }
        //4、未使用优惠券
        if (orderParam.getCouponId() == null) {
            for (OmsOrderItem omsOrderItem : orderItemList) {
                omsOrderItem.setCouponAmount(new BigDecimal("0"));
            }
        } else {
            //使用了优惠券
            SmsCouponHistoryDetail couponHistoryDetail = getUseCoupon(cartPromotionItemList, orderParam.getCouponId());
            if (couponHistoryDetail == null) {
                Asserts.fail("该优惠券不可用！");
            }
            //对下单商品的优惠券进行处理
            handleCouponAmount(orderItemList, couponHistoryDetail);
        }
        //5、判断是否使用了积分
        if (orderParam.getUseIntegration() == null || orderParam.getUseIntegration().equals(0)) {
            //不使用积分
            for (OmsOrderItem orderItem : orderItemList) {
                orderItem.setIntegrationAmount(new BigDecimal("0"));
            }
        } else {
            //使用了积分
            BigDecimal totalAmount = calcTotalAmount(orderItemList);
            BigDecimal integrationAmount = getUseIntegrationAmount(orderParam.getUseIntegration(), totalAmount, currentMember, orderParam.getCouponId() != null);
            if (integrationAmount.compareTo(new BigDecimal("0")) == 0) {
                Asserts.fail("积分不可使用！");
            } else {
                for (OmsOrderItem orderItem : orderItemList) {
                    BigDecimal perAmount = orderItem.getProductPrice().divide(totalAmount, 3, RoundingMode.HALF_EVEN).multiply(integrationAmount);
                    orderItem.setIntegrationAmount(perAmount);
                }
            }
        }
        //6、计算每个order_item的实付金额
        handleRealAmount(orderItemList);
        //7、进行每个商品的库存锁定
        lockStock(cartPromotionItemList);
        //8、根据商品合计、运费、活动优惠、优惠券、积分计算应付金额
        OmsOrder order = new OmsOrder();
        order.setDiscountAmount(new BigDecimal("0"));
        order.setFreightAmount(new BigDecimal("0"));
        order.setTotalAmount(calcTotalAmount(orderItemList));
        order.setPromotionAmount(calcPromotionAmount(orderItemList));
        order.setPromotionInfo(getOrderPromotionInfo(orderItemList));
        if (orderParam.getCouponId() == null) {
            order.setCouponAmount(new BigDecimal(0));
        } else {
            order.setCouponId(orderParam.getCouponId());
            order.setCouponAmount(calcCouponAmount(orderItemList));
        }
        if (orderParam.getUseIntegration() == null) {
            order.setIntegration(0);
            order.setIntegrationAmount(new BigDecimal(0));
        } else {
            order.setIntegration(orderParam.getUseIntegration());
            order.setIntegrationAmount(calcIntegrationAmount(orderItemList));
        }
        //计算实际支付金额
        order.setPayAmount(calcPayAmount(order));
        //9、设置orderItem其他相关信息
        order.setMemberId(currentMember.getId());
        order.setCreateTime(new Date());
        order.setMemberUsername(currentMember.getUsername());
        //设置支付方式 支付方式：0->未支付；1->支付宝；2->微信
        order.setPayType(orderParam.getPayType());
        //设置订单来源  0->PC订单；1->app订单
        order.setSourceType(1);
        //设置订单状态  0->待付款；1->待发货；2->已发货；3->已完成；4->已关闭；5->无效订单
        order.setStatus(0);
        //设置订单类型  0->正常订单；1->秒杀订单
        order.setOrderType(0);
        //10、设置收货人相关信息 包括姓名、电话、邮编、地址
        UmsMemberReceiveAddress umsMemberReceiveAddress = umsMemberReceiveAddressService.getItem(orderParam.getMemberReceiveAddressId());
        order.setReceiverName(umsMemberReceiveAddress.getName());
        order.setReceiverPhone(umsMemberReceiveAddress.getPhoneNumber());
        order.setReceiverPostCode(umsMemberReceiveAddress.getPostCode());
        order.setReceiverProvince(umsMemberReceiveAddress.getProvince());
        order.setReceiverCity(umsMemberReceiveAddress.getCity());
        order.setReceiverRegion(umsMemberReceiveAddress.getRegion());
        order.setReceiverDetailAddress(umsMemberReceiveAddress.getDetailAddress());
        //设置确认收货状态
        order.setConfirmStatus(0);
        //设置删状态
        order.setDeleteStatus(0);
        //计算赠送积分
        order.setIntegration(calcGifIntegration(orderItemList));
        //计算赠送成长值
        order.setGrowth(calcGiftGrowth(orderItemList));
        //生成订单号
        order.setOrderSn(generateOrderSn(order));
        //设置自动收货天数
        List<OmsOrderSetting> orderSettings = omsOrderSettingMapper.selectByExample(new OmsOrderSettingExample());
        if (!CollectionUtils.isEmpty(orderSettings)) {
            order.setAutoConfirmDay(orderSettings.get(0).getConfirmOvertime());
        }
        //11、插入order和orderItem相关表信息
        omsOrderMapper.insert(order);
        for (OmsOrderItem orderItem : orderItemList) {
            orderItem.setOrderId(order.getId());
            orderItem.setOrderSn(order.getOrderSn());
        }
        //批量进行订单商品的插入
        omsOrderItemDao.insertList(orderItemList);
        //12、使用了优惠券需要更新优惠券使用状态
        if (orderParam.getCouponId() != null) {
            updateCouponStatus(orderParam.getCouponId(), currentMember.getId(), 1);
        }
        //13、使用了积分需要扣除积分
        if (orderParam.getUseIntegration() != null) {
            order.setUseIntegration(orderParam.getUseIntegration());
            umsMemberService.updateIntegration(currentMember.getId(), currentMember.getIntegration() - orderParam.getUseIntegration());
        }
        //14、删除购物车中的下单商品
        deleteCartItemList(cartPromotionItemList, currentMember);
        //15、发送延迟消息取消订单
        sendDelayMessageCancelOrder(order.getId());
        Map<String, Object> result = new HashMap<>();
        result.put("order", order);
        result.put("orderItemList", orderItemList);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.DEFAULT, propagation = Propagation.REQUIRED)
    public void cancelOrder(Long orderId) {
        //1、根据订单id获取到订单（订单是待付款且未删除）
        OmsOrderExample example = new OmsOrderExample();
        example.createCriteria().andIdEqualTo(orderId).andDeleteStatusEqualTo(0).andStatusEqualTo(0);
        List<OmsOrder> cancelOrderList = omsOrderMapper.selectByExample(example);
        if (!CollectionUtils.isEmpty(cancelOrderList)) {
            return;
        }
        OmsOrder cancelOrder = cancelOrderList.get(0);
        if (cancelOrder != null) {
            //2、修改订单状态为取消状态
            cancelOrder.setStatus(4);
            omsOrderMapper.updateByPrimaryKeySelective(cancelOrder);
            OmsOrderItemExample orderItemExample = new OmsOrderItemExample();
            orderItemExample.createCriteria().andOrderIdEqualTo(orderId);
            List<OmsOrderItem> orderItemList = omsOrderItemMapper.selectByExample(orderItemExample);
            //3、解除订单中商品的锁定库存
            if (!CollectionUtils.isEmpty(orderItemList)) {
                portalOrderDao.releaseSkuStockLock(orderItemList);
            }
            //4、修改优惠券使用状态
            updateCouponStatus(cancelOrder.getCouponId(), cancelOrder.getMemberId(), 0);
            //5、返还使用积分
            if (cancelOrder.getUseIntegration() != null) {
                UmsMember member = umsMemberService.getById(cancelOrder.getMemberId());
                umsMemberService.updateIntegration(cancelOrder.getMemberId(), member.getIntegration() + cancelOrder.getUseIntegration());
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.DEFAULT, propagation = Propagation.REQUIRED)
    public Integer paySuccess(Long orderId, Integer payType) {
        //修改订单支付状态
        OmsOrder order = new OmsOrder();
        order.setId(orderId);
        order.setStatus(1);
        order.setPayType(payType);
        order.setPaymentTime(new Date());
        omsOrderMapper.updateByPrimaryKeySelective(order);
        //恢复所有下单商品的锁定库存，扣减真实库存
        OmsOrderDetail orderDetail = portalOrderDao.getDetail(orderId);
        int count = portalOrderDao.updateSkuStock(orderDetail.getOrderItemList());
        return count;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.DEFAULT, propagation = Propagation.REQUIRED)
    public Integer cancelTimeOutOrder() {
        Integer count = 0;
        OmsOrderSetting orderSetting = omsOrderSettingMapper.selectByPrimaryKey(1L);
        //查询超时、未支付的订单及订单详情
        List<OmsOrderDetail> timeOutOrders = portalOrderDao.getTimeOutOrders(orderSetting.getNormalOrderOvertime());
        if (!CollectionUtils.isEmpty(timeOutOrders)) {
            return count;
        }
        //修改订单状态为交易取消
        List<Long> ids = new ArrayList<>();
        for (OmsOrderDetail timeOutOrder : timeOutOrders) {
            ids.add(timeOutOrder.getId());
        }
        portalOrderDao.updateOrderStatus(ids, 4);
        for (OmsOrderDetail timeOutOrder : timeOutOrders) {
            //解锁订单商品库存锁定
            portalOrderDao.releaseSkuStockLock(timeOutOrder.getOrderItemList());
            //修改优惠券使用状态
            updateCouponStatus(timeOutOrder.getCouponId(), timeOutOrder.getMemberId(), 0);
            //返还使用积分
            if (timeOutOrder.getUseIntegration() != null) {
                UmsMember member = umsMemberService.getById(timeOutOrder.getMemberId());
                umsMemberService.updateIntegration(timeOutOrder.getMemberId(), member.getIntegration() + timeOutOrder.getUseIntegration());
            }
        }
        return timeOutOrders.size();
    }

    /**
     * 发送延迟消息取消订单
     *
     * @param orderId
     */
    @Override
    public void sendDelayMessageCancelOrder(Long orderId) {
        //获取订单超时时间
        OmsOrderSetting orderSetting = omsOrderSettingMapper.selectByPrimaryKey(1L);
        long delayTimes = orderSetting.getNormalOrderOvertime() * 60 * 1000;
        //发送延迟消息
        cancelOrderSender.sendMessage(orderId, delayTimes);
    }

    @Override
    public OmsOrderDetail detail(Long orderId) {
//        return portalOrderDao.getDetail(orderId);
        OmsOrder omsOrder = omsOrderMapper.selectByPrimaryKey(orderId);
        OmsOrderItemExample example = new OmsOrderItemExample();
        example.createCriteria().andOrderIdEqualTo(orderId);
        List<OmsOrderItem> orderItemList = omsOrderItemMapper.selectByExample(example);
        OmsOrderDetail orderDetail = new OmsOrderDetail();
        BeanUtils.copyProperties(omsOrder, orderDetail);
        orderDetail.setOrderItemList(orderItemList);
        return orderDetail;
    }

    @Override
    public void confirmReceiveOrder(Long orderId) {
        UmsMember member = umsMemberService.getCurrentMember();
        final OmsOrder order = omsOrderMapper.selectByPrimaryKey(orderId);
        if (!member.getId().equals(order.getMemberId())) {
            Asserts.fail("不能确认他人订单！");
        }
        if (order.getStatus() != 2) {
            Asserts.fail("该订单还未发货！");
        }
        order.setStatus(3);
        order.setConfirmStatus(1);
        order.setReceiveTime(new Date());
        omsOrderMapper.updateByPrimaryKeySelective(order);
    }

    @Override
    public void deleteOrder(Long orderId) {
        UmsMember member = umsMemberService.getCurrentMember();
        OmsOrder order = omsOrderMapper.selectByPrimaryKey(orderId);
        if (!member.getId().equals(order.getMemberId())) {
            Asserts.fail("不能删除他人订单！");
        }
        if (order.getStatus() == 3 || order.getStatus() == 4) {
            order.setStatus(1);
            omsOrderMapper.updateByPrimaryKey(order);
        } else {
            Asserts.fail("只能删除已完成或已关闭的订单！");
        }
    }

    @Override
    public CommonPage<OmsOrderDetail> list(Integer status, Integer pageNum, Integer pageSize) {
        if (status == -1) {
            status = null;
        }
        UmsMember currentMember = umsMemberService.getCurrentMember();
        PageHelper.startPage(pageNum, pageSize);
        OmsOrderExample orderExample = new OmsOrderExample();
        final OmsOrderExample.Criteria orderExampleCriteria = orderExample.createCriteria();
        orderExampleCriteria.andDeleteStatusEqualTo(0)
                .andMemberIdEqualTo(currentMember.getId());
        if (status != null) {
            orderExampleCriteria.andStatusEqualTo(status);
        }
        orderExample.setOrderByClause("create_time desc");
        List<OmsOrder> omsOrderList = omsOrderMapper.selectByExample(orderExample);
        CommonPage<OmsOrder> orderPage = CommonPage.restPage(omsOrderList);
        //设置分页信息
        CommonPage<OmsOrderDetail> resultPage = new CommonPage<>();
        resultPage.setPageNum(orderPage.getPageNum());
        resultPage.setPageSize(orderPage.getPageSize());
        resultPage.setTotal(orderPage.getTotal());
        resultPage.setTotalPage(orderPage.getTotalPage());
        if (CollUtil.isEmpty(omsOrderList)) {
            return resultPage;
        }
        //设置数据信息
        List<Long> orderIds = omsOrderList.stream().map(OmsOrder::getId).collect(Collectors.toList());
        OmsOrderItemExample orderItemExample = new OmsOrderItemExample();
        orderItemExample.createCriteria().andOrderIdIn(orderIds);
        List<OmsOrderItem> orderItemList = omsOrderItemMapper.selectByExample(orderItemExample);
        List<OmsOrderDetail> omsOrderDetailList = new ArrayList<>();
        for (OmsOrder omsOrder : omsOrderList) {
            OmsOrderDetail orderDetail = new OmsOrderDetail();
            BeanUtils.copyProperties(omsOrder, orderDetail);
            List<OmsOrderItem> relatedItemList = orderItemList.stream().filter(item -> item.getOrderId().equals(orderDetail.getId())).collect(Collectors.toList());
            orderDetail.setOrderItemList(relatedItemList);
            omsOrderDetailList.add(orderDetail);
        }
        resultPage.setList(omsOrderDetailList);
        return resultPage;
    }

    /**
     * 删除购物车中的已下单的商品
     *
     * @param cartPromotionItemList
     * @param currentMember
     */
    private void deleteCartItemList(List<CartPromotionItem> cartPromotionItemList, UmsMember currentMember) {
        List<Long> ids = new ArrayList<>();
        for (CartPromotionItem promotionItem : cartPromotionItemList) {
            ids.add(promotionItem.getId());
        }
        omsCartItemService.delete(currentMember.getId(), ids);
    }

    /**
     * 将优惠券信息改为指定状态
     *
     * @param couponId  优惠券id
     * @param memberId  会员id
     * @param useStatus 使用状态 0 -> 未使用  1 -> 已使用
     */
    private void updateCouponStatus(Long couponId, Long memberId, int useStatus) {
        if (couponId == null) {
            return;
        }
        //查询用户的指定优惠券
        SmsCouponHistoryExample example = new SmsCouponHistoryExample();
        example.createCriteria().andMemberIdEqualTo(memberId)
                .andCouponIdEqualTo(couponId);
        List<SmsCouponHistory> couponHistoryList = smsCouponHistoryMapper.selectByExample(example);
        if (!CollectionUtils.isEmpty(couponHistoryList)) {
            //只取一张优惠券来使用
            SmsCouponHistory smsCouponHistory = couponHistoryList.get(0);
            smsCouponHistory.setUseStatus(useStatus);
            smsCouponHistory.setUseTime(new Date());
            smsCouponHistoryMapper.updateByPrimaryKeySelective(smsCouponHistory);
        }
    }

    /**
     * 设置生成订单编号
     * 生成18位订单编号: 8位日期 + 2位平台号码 + 2位支付方式 + 6位以上自增id
     *
     * @param order
     * @return
     */
    private String generateOrderSn(OmsOrder order) {
        StringBuilder sb = new StringBuilder();
        String date = new SimpleDateFormat("yyyyMMdd").format(new Date());
        String key = REDIS_KEY_ORDER_ID + ":" + REDIS_DATABASE + date;
        Long increment = redisService.incr(key, 1);
        sb.append(date);
        sb.append(String.format("%02d", order.getSourceType()));
        sb.append(String.format("%02d", order.getPayType()));
        String incrementStr = increment.toString();
        if (incrementStr.length() <= 6) {
            sb.append(String.format("%06d", increment));
        } else {
            sb.append(incrementStr);
        }
        return sb.toString();
    }

    /**
     * 计算订单可以获得的成长值
     *
     * @param orderItemList
     * @return
     */
    private Integer calcGiftGrowth(List<OmsOrderItem> orderItemList) {
        int sum = 0;
        for (OmsOrderItem orderItem : orderItemList) {
            sum += orderItem.getGiftGrowth() * orderItem.getProductQuantity();
        }
        return sum;
    }

    /**
     * 计算订单可以获得的积分
     *
     * @param orderItemList
     * @return
     */
    private Integer calcGifIntegration(List<OmsOrderItem> orderItemList) {
        int sum = 0;
        for (OmsOrderItem orderItem : orderItemList) {
            sum += orderItem.getGiftIntegration() * orderItem.getProductQuantity();
        }
        return sum;
    }

    /**
     * 计算实际支付金额
     *
     * @param order
     * @return
     */
    private BigDecimal calcPayAmount(OmsOrder order) {
        //总金额 + 运费金额 + 促销优惠 - 优惠券优惠 - 积分抵扣
        BigDecimal payAmount = order.getTotalAmount()
                .add(order.getFreightAmount())
                .subtract(order.getPromotionAmount())
                .subtract(order.getCouponAmount())
                .subtract(order.getIntegrationAmount());
        return payAmount;
    }

    /**
     * 计算订单积分金额
     *
     * @param orderItemList
     * @return
     */
    private BigDecimal calcIntegrationAmount(List<OmsOrderItem> orderItemList) {
        BigDecimal integrationAmount = new BigDecimal(0);
        for (OmsOrderItem orderItem : orderItemList) {
            if (orderItem.getIntegrationAmount() != null) {
                integrationAmount = integrationAmount.add(orderItem.getIntegrationAmount().multiply(new BigDecimal(orderItem.getProductQuantity())));
            }
        }
        return integrationAmount;
    }

    /**
     * 计算订单优惠券金额
     *
     * @param orderItemList
     * @return
     */
    private BigDecimal calcCouponAmount(List<OmsOrderItem> orderItemList) {
        BigDecimal couponAmount = new BigDecimal(0);
        for (OmsOrderItem orderItem : orderItemList) {
            if (orderItem.getCouponAmount() != null) {
                couponAmount = couponAmount.add(orderItem.getCouponAmount().multiply(new BigDecimal(orderItem.getProductQuantity())));
            }
        }
        return couponAmount;
    }

    /**
     * 获取订单促销信息
     *
     * @param orderItemList
     * @return
     */
    private String getOrderPromotionInfo(List<OmsOrderItem> orderItemList) {
        StringBuilder sb = new StringBuilder();
        for (OmsOrderItem orderItem : orderItemList) {
            sb.append(orderItem.getPromotionName());
            sb.append(";");
        }
        String result = sb.toString();
        if (result.endsWith(";")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /**
     * 计算订单活动优惠
     *
     * @param orderItemList
     * @return
     */
    private BigDecimal calcPromotionAmount(List<OmsOrderItem> orderItemList) {
        BigDecimal promotionAmount = new BigDecimal(0);
        for (OmsOrderItem orderItem : orderItemList) {
            if (orderItem.getPromotionAmount() != null) {
                promotionAmount = promotionAmount.add(orderItem.getPromotionAmount().multiply(new BigDecimal(orderItem.getProductQuantity())));
            }
        }
        return promotionAmount;
    }

    /**
     * 锁定下单商品的所有库存
     *
     * @param cartPromotionItemList
     */
    private void lockStock(List<CartPromotionItem> cartPromotionItemList) {
        for (CartPromotionItem cartPromotionItem : cartPromotionItemList) {
            PmsSkuStock skuStock = pmsSkuStockMapper.selectByPrimaryKey(cartPromotionItem.getProductSkuId());
            skuStock.setLockStock(skuStock.getLockStock() + cartPromotionItem.getQuantity());
            pmsSkuStockMapper.updateByPrimaryKeySelective(skuStock);
        }
    }

    /**
     * 计算每个order_item的实付金额
     *
     * @param orderItemList
     */
    private void handleRealAmount(List<OmsOrderItem> orderItemList) {
        for (OmsOrderItem orderItem : orderItemList) {
            //原价-促销优惠-优惠券抵扣-积分抵扣
            BigDecimal realAmount = orderItem.getProductPrice()
                    .subtract(orderItem.getPromotionAmount())
                    .subtract(orderItem.getCouponAmount())
                    .subtract(orderItem.getIntegrationAmount());
            orderItem.setRealAmount(realAmount);
        }
    }

    /**
     * 获取可用积分抵扣金额
     *
     * @param useIntegration 使用的积分总量
     * @param totalAmount    订单总金额
     * @param currentMember  使用的用户
     * @param hasCoupon      是否已经使用了优惠券
     * @return
     */
    private BigDecimal getUseIntegrationAmount(Integer useIntegration, BigDecimal totalAmount, UmsMember currentMember, boolean hasCoupon) {
        BigDecimal zeroAmount = new BigDecimal(0);
        //判断用户是否有这么多积分
        if (useIntegration.compareTo(currentMember.getIntegration()) > 0) {
            return zeroAmount;
        }
        //根据积分使用规则判断是否可用
        //是否可以与优惠券共用
        UmsIntegrationConsumeSetting integrationConsumeSetting = umsIntegrationConsumeSettingMapper.selectByPrimaryKey(1L);
        if (hasCoupon && integrationConsumeSetting.getCouponStatus().equals(0)) {
            //不可以与优惠券共用
            return zeroAmount;
        }
        //是否达到最低使用积分门槛
        if (useIntegration.compareTo(integrationConsumeSetting.getUseUnit()) < 0) {
            return zeroAmount;
        }
        //是否超过订单抵用最高百分比
        BigDecimal integrationAmount = new BigDecimal(useIntegration).divide(new BigDecimal(integrationConsumeSetting.getUseUnit()), 2, RoundingMode.HALF_EVEN);
        BigDecimal maxPercent = new BigDecimal(integrationConsumeSetting.getMaxPercentPerOrder()).divide(new BigDecimal(100), 2, RoundingMode.HALF_EVEN);
        if (integrationAmount.compareTo(totalAmount.multiply(maxPercent)) > 0) {
            return zeroAmount;
        }
        return integrationAmount;
    }

    /**
     * 对优惠券优惠进行处理
     *
     * @param orderItemList
     * @param couponHistoryDetail
     */
    private void handleCouponAmount(List<OmsOrderItem> orderItemList, SmsCouponHistoryDetail couponHistoryDetail) {
        SmsCoupon coupon = couponHistoryDetail.getCoupon();
        //全场通用
        if (coupon.getUseType().equals(0)) {
            calcPerCouponAmount(orderItemList, coupon);
            //指定分类
        } else if (coupon.getUseType().equals(1)) {
            List<OmsOrderItem> couponOrderItemList = getCouponOrderItemByRelation(couponHistoryDetail, orderItemList, 0);
            calcPerCouponAmount(couponOrderItemList, coupon);
            //指定商品
        } else if (coupon.getUseType().equals(2)) {
            List<OmsOrderItem> couponOrderItemList = getCouponOrderItemByRelation(couponHistoryDetail, orderItemList, 1);
            calcPerCouponAmount(couponOrderItemList, coupon);
        }
    }

    /**
     * 获取与优惠券有关系的下单商品
     *
     * @param couponHistoryDetail
     * @param orderItemList
     * @param type
     * @return
     */
    private List<OmsOrderItem> getCouponOrderItemByRelation(SmsCouponHistoryDetail couponHistoryDetail, List<OmsOrderItem> orderItemList, int type) {
        List<OmsOrderItem> result = new ArrayList<>();
        if (type == 0) {
            List<Long> categoryIdList = new ArrayList<>();
            for (SmsCouponProductCategoryRelation productCategoryRelation : couponHistoryDetail.getCategoryRelationList()) {
                categoryIdList.add(productCategoryRelation.getProductCategoryId());
            }
            for (OmsOrderItem orderItem : orderItemList) {
                if (categoryIdList.contains(orderItem.getProductCategoryId())) {
                    result.add(orderItem);
                } else {
                    orderItem.setCouponAmount(new BigDecimal("0"));
                }
            }
        } else if (type == 1) {
            List<Long> productIdList = new ArrayList<>();
            for (SmsCouponProductRelation productRelation : couponHistoryDetail.getProductRelationList()) {
                productIdList.add(productRelation.getProductId());
            }
            for (OmsOrderItem orderItem : orderItemList) {
                if (productIdList.contains(orderItem.getProductId())) {
                    result.add(orderItem);
                } else {
                    orderItem.setCouponAmount(new BigDecimal("0"));
                }
            }
        }
        return result;
    }

    /**
     * 对每个下单商品进行优惠券金额分摊的计算
     *
     * @param orderItemList
     * @param coupon
     */
    private void calcPerCouponAmount(List<OmsOrderItem> orderItemList, SmsCoupon coupon) {
        BigDecimal totalAmount = calcTotalAmount(orderItemList);
        for (OmsOrderItem orderItem : orderItemList) {
            //（商品价格/可用商品总价）*优惠券面额
            BigDecimal couponAmount = orderItem.getProductPrice().divide(totalAmount, 3, RoundingMode.HALF_EVEN).multiply(coupon.getAmount());
            orderItem.setCouponAmount(couponAmount);
        }

    }

    /**
     * 计算总金额
     *
     * @param orderItemList
     * @return
     */
    private BigDecimal calcTotalAmount(List<OmsOrderItem> orderItemList) {
        BigDecimal totalAmount = new BigDecimal("0");
        for (OmsOrderItem item : orderItemList) {
            totalAmount = totalAmount.add(item.getProductPrice().multiply(new BigDecimal(item.getProductQuantity())));
        }
        return totalAmount;
    }

    /**
     * 获取该用户使用的优惠券
     *
     * @param cartPromotionItemList
     * @param couponId
     * @return
     */
    private SmsCouponHistoryDetail getUseCoupon(List<CartPromotionItem> cartPromotionItemList, Long couponId) {
        //获取该用户所有的可使用的优惠券信息
        List<SmsCouponHistoryDetail> smsCouponHistoryDetails = umsMemberCouponService.listCart(cartPromotionItemList, 1);
        //找出当前使用了的那个优惠券信息
        for (SmsCouponHistoryDetail couponHistoryDetail : smsCouponHistoryDetails) {
            if (couponHistoryDetail.getCoupon().getId().equals(couponId)) {
                return couponHistoryDetail;
            }
        }
        return null;
    }

    /**
     * 判断购物车中商品是否都有库存
     *
     * @param cartPromotionItemList
     * @return
     */
    private boolean hasStock(List<CartPromotionItem> cartPromotionItemList) {
        for (CartPromotionItem cartPromotionItem : cartPromotionItemList) {
            if (cartPromotionItem.getRealStock() == null || cartPromotionItem.getRealStock() <= 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 计算购物车中商品的价格
     *
     * @param cartPromotionItemList
     * @return
     */
    private ConfirmOrderResult.CalcAmount calcCartAmount(List<CartPromotionItem> cartPromotionItemList) {
        ConfirmOrderResult.CalcAmount calcAmount = new ConfirmOrderResult.CalcAmount();
        calcAmount.setFreightAmount(new BigDecimal("0"));
        BigDecimal totalAmount = new BigDecimal("0");
        BigDecimal promotionAmount = new BigDecimal("0");
        for (CartPromotionItem cartPromotionItem : cartPromotionItemList) {
            totalAmount = totalAmount.add(cartPromotionItem.getPrice().multiply(new BigDecimal(cartPromotionItem.getQuantity())));
            promotionAmount = promotionAmount.add(cartPromotionItem.getReduceAmount().multiply(new BigDecimal(cartPromotionItem.getQuantity())));
        }
        calcAmount.setTotalAmount(totalAmount);
        calcAmount.setPromotionAmount(promotionAmount);
        calcAmount.setPayAmount(totalAmount.subtract(promotionAmount));
        return calcAmount;
    }
}
