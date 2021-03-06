package com.tongbanjie.tevent.common;

/**
 * 支持权重接口 <p>
 * 数值越大，权重越大
 * 取值范围 1 ~ Short.MAX_VALUE
 *
 * @author zixiao
 * @date 16/10/19
 */
public interface Weighable {

    /**
     * 权重默认值
     */
    short DEFAULT_WEIGHT = 1;

    short getWeight();

    void setWeight(short weight);

}
