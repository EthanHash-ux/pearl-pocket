package ai.pearl.wallet;

import java.math.BigDecimal;
import java.math.MathContext;

/** Scenario arithmetic from user estimates, not a hashrate-to-reward prediction. */
final class MiningProfit {
    final BigDecimal revenue, electricity, rent, net, days30, days90, breakEvenPrice;
    static BigDecimal number(String value){
        if(value==null||!value.matches("[0-9]{1,12}(\\.[0-9]{1,8})?"))throw new IllegalArgumentException("请输入非负数字，最多 8 位小数");return new BigDecimal(value);
    }
    MiningProfit(String prlPerDay,String priceCny,String watts,String electricityCny,String feePercent,String rentCny){
        BigDecimal coins=number(prlPerDay),price=number(priceCny),power=number(watts),rate=number(electricityCny),fee=number(feePercent);
        if(price.signum()<=0)throw new IllegalArgumentException("PRL 价格需大于 0");
        if(fee.compareTo(new BigDecimal("100"))>=0)throw new IllegalArgumentException("矿池费率需小于 100%");
        BigDecimal afterFee=coins.multiply(BigDecimal.ONE.subtract(fee.movePointLeft(2)));
        revenue=afterFee.multiply(price);electricity=power.movePointLeft(3).multiply(new BigDecimal("24")).multiply(rate);
        rent=number(rentCny);net=revenue.subtract(electricity).subtract(rent);days30=net.multiply(new BigDecimal("30"));days90=net.multiply(new BigDecimal("90"));
        breakEvenPrice=afterFee.signum()==0?null:electricity.add(rent).divide(afterFee,MathContext.DECIMAL64);
    }
}
