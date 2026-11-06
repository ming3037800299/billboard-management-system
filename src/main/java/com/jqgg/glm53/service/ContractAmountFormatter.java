package com.jqgg.glm53.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 合同金额格式化：人民币大写与千分位展示。
 */
public final class ContractAmountFormatter {

    private static final String[] DIGITS = {"零", "壹", "贰", "叁", "肆", "伍", "陆", "柒", "捌", "玖"};
    private static final String[] SMALL_UNITS = {"", "拾", "佰", "仟"};
    private static final String[] BIG_UNITS = {"", "万", "亿", "万亿"};

    private ContractAmountFormatter() {
    }

    /** 金额（元）→ 人民币大写，如 12345.67 → 壹万贰仟叁佰肆拾伍元陆角柒分。 */
    public static String toChinese(BigDecimal amount) {
        if (amount == null) {
            return "";
        }
        BigDecimal value = amount.setScale(2, RoundingMode.HALF_UP);
        boolean negative = value.signum() < 0;
        value = value.abs();
        long integer = value.longValue();
        int decimal = value.subtract(BigDecimal.valueOf(integer)).movePointRight(2).intValue();

        StringBuilder sb = new StringBuilder();
        if (negative) {
            sb.append("负");
        }
        if (integer == 0) {
            sb.append("零元");
        } else {
            sb.append(integerToChinese(integer)).append("元");
        }

        int jiao = decimal / 10;
        int fen = decimal % 10;
        if (jiao == 0 && fen == 0) {
            sb.append("整");
        } else {
            if (jiao > 0) {
                sb.append(DIGITS[jiao]).append("角");
            } else if (fen > 0) {
                sb.append("零");
            }
            if (fen > 0) {
                sb.append(DIGITS[fen]).append("分");
            } else {
                sb.append("整");
            }
        }
        return sb.toString();
    }

    /** 金额（元）→ 1,234,567.00。 */
    public static String toGrouped(BigDecimal amount) {
        if (amount == null) {
            return "";
        }
        return String.format("%,.2f", amount);
    }

    private static String integerToChinese(long number) {
        if (number == 0) {
            return "零";
        }
        StringBuilder result = new StringBuilder();
        int bigIndex = 0;
        boolean needZero = false;
        while (number > 0 && bigIndex < BIG_UNITS.length) {
            int group = (int) (number % 10000);
            if (group != 0) {
                String groupText = groupToChinese(group);
                if (needZero && result.length() > 0) {
                    result.insert(0, "零");
                }
                result.insert(0, groupText + BIG_UNITS[bigIndex]);
                needZero = group < 1000;
            } else if (result.length() > 0) {
                needZero = true;
            }
            number /= 10000;
            bigIndex++;
        }
        return result.toString();
    }

    private static String groupToChinese(int group) {
        StringBuilder sb = new StringBuilder();
        boolean zero = false;
        int position = 3;
        while (position >= 0) {
            int digit = (group / (int) Math.pow(10, position)) % 10;
            if (digit == 0) {
                if (sb.length() > 0) {
                    zero = true;
                }
            } else {
                if (zero) {
                    sb.append("零");
                    zero = false;
                }
                sb.append(DIGITS[digit]).append(SMALL_UNITS[position]);
            }
            position--;
        }
        return sb.toString();
    }
}
