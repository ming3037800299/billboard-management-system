package com.jqgg.glm53.service;

import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblBorders;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblWidth;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblWidth;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;

/**
 * 系统内置的默认《广告牌租赁合同》Word 模板。
 * 模板中的 {{字段}} 由 poi-tl 在生成合同时替换；
 * {{@甲方签章}} 与 {{@乙方签名}} 为图片占位符。
 */
public final class DefaultContractTemplateFactory {

    private DefaultContractTemplateFactory() {
    }

    /** 生成默认模板的 docx 字节内容。 */
    public static byte[] build() {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            applyPageMargins(doc);

            XWPFParagraph title = doc.createParagraph();
            title.setAlignment(ParagraphAlignment.CENTER);
            title.setSpacingAfter(240);
            XWPFRun titleRun = title.createRun();
            titleRun.setText("广告牌租赁合同");
            titleRun.setBold(true);
            titleRun.setFontSize(22);
            setChineseFont(titleRun);

            paragraph(doc, "合同编号：{{合同编号}}", false, 0);
            paragraph(doc, "签订地点：{{签订地点}}", false, 0);
            paragraph(doc, "签订日期：{{签订日期}}", false, 200);

            section(doc, "甲方（出租方）");
            paragraph(doc, "名称：{{甲方名称}}", false, 0);
            paragraph(doc, "统一社会信用代码：{{甲方统一社会信用代码}}", false, 0);
            paragraph(doc, "地址：{{甲方地址}}", false, 0);
            paragraph(doc, "法定代表人：{{甲方法定代表人}}", false, 0);
            paragraph(doc, "联系人：{{甲方联系人}}        联系电话：{{甲方电话}}", false, 0);
            paragraph(doc, "开户行：{{甲方开户行}}", false, 0);
            paragraph(doc, "银行账号：{{甲方账号}}", false, 180);

            section(doc, "乙方（承租方）");
            paragraph(doc, "名称：{{乙方名称}}", false, 0);
            paragraph(doc, "主体类型：{{乙方类型}}", false, 0);
            paragraph(doc, "证件号码：{{乙方证件号码}}", false, 0);
            paragraph(doc, "地址：{{乙方地址}}", false, 0);
            paragraph(doc, "联系人：{{乙方联系人}}        联系电话：{{乙方电话}}", false, 0);
            paragraph(doc, "电子邮箱：{{乙方邮箱}}", false, 200);

            paragraph(doc,
                    "鉴于甲方拥有本合同项下广告牌的合法出租权利，乙方拟租赁该广告牌用于广告发布，"
                            + "双方根据《中华人民共和国民法典》及相关法律法规，经友好协商，达成如下协议：",
                    false, 200);

            section(doc, "第一条  租赁标的");
            paragraph(doc, "1.1  广告牌信息如下：", false, 80);
            createBillboardTable(doc);
            paragraph(doc,
                    "1.2  乙方确认已实地查看或通过甲方提供的资料充分了解广告牌现状，并同意按现状承租。",
                    false, 160);

            section(doc, "第二条  租赁期限");
            paragraph(doc, "2.1  租赁期限自 {{租赁开始日期}} 起至 {{租赁结束日期}} 止，共计 {{租赁时长}}。", false, 0);
            paragraph(doc,
                    "2.2  租赁期满前，乙方如需续租，应提前 30 日书面通知甲方，双方另行签订续租协议。",
                    false, 160);

            section(doc, "第三条  租金及支付");
            paragraph(doc, "3.1  本合同租金总额为人民币 {{合同金额}} 元（大写：{{合同金额大写}}）。", false, 0);
            paragraph(doc, "3.2  定金为人民币 {{定金金额}} 元。", false, 0);
            paragraph(doc, "3.3  付款方式：{{付款方式}}。", false, 0);
            paragraph(doc,
                    "3.4  乙方应按约定及时支付租金；逾期支付的，每逾期一日按应付未付金额的 0.05% "
                            + "向甲方支付违约金。",
                    false, 160);

            section(doc, "第四条  广告内容与发布要求");
            paragraph(doc, "4.1  乙方发布广告内容为：{{广告内容}}。", false, 0);
            paragraph(doc,
                    "4.2  乙方保证广告内容真实、合法，并自行取得必要的审批、备案手续；"
                            + "因广告内容违法或侵权产生的责任由乙方承担。",
                    false, 0);
            paragraph(doc,
                    "4.3  广告画面的制作、安装、更换及维护费用由乙方承担，甲方应提供必要的配合。",
                    false, 160);

            section(doc, "第五条  双方权利义务");
            paragraph(doc,
                    "5.1  甲方应保证对租赁广告牌享有合法的出租权利，并按约定向乙方提供广告牌及必要的通电、维护条件。",
                    false, 0);
            paragraph(doc, "5.2  乙方应按约定用途使用广告牌，不得擅自改变结构或转租、转让。", false, 0);
            paragraph(doc,
                    "5.3  因政府规划、道路改造、不可抗力等原因导致广告牌无法继续使用的，双方应协商解决，"
                            + "甲方按实际使用时间退还剩余租金。",
                    false, 160);

            section(doc, "第六条  违约责任");
            paragraph(doc, "6.1  任何一方违反本合同约定，应赔偿守约方因此遭受的实际损失。", false, 0);
            paragraph(doc, "6.2  乙方逾期支付租金超过 15 日的，甲方有权解除合同，已收定金不予退还。", false, 0);
            paragraph(doc,
                    "6.3  甲方无正当理由提前收回广告牌的，应退还剩余租金并赔偿乙方因此遭受的直接损失。",
                    false, 160);

            section(doc, "第七条  争议解决");
            paragraph(doc,
                    "本合同履行过程中发生争议，双方应友好协商解决；协商不成的，任何一方均可向合同签订地"
                            + "有管辖权的人民法院提起诉讼。",
                    false, 160);

            section(doc, "第八条  其他约定");
            paragraph(doc, "8.1  特殊约定：{{特殊约定}}。", false, 0);
            paragraph(doc, "8.2  备注：{{备注}}。", false, 0);
            paragraph(doc, "8.3  本合同自双方签字（或盖章）之日起生效。", false, 0);
            paragraph(doc, "8.4  本合同一式贰份，甲乙双方各执壹份，具有同等法律效力。", false, 240);

            createSignatureTable(doc);

            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("生成默认合同模板失败", e);
        }
    }

    private static void createBillboardTable(XWPFDocument doc) {
        // 两列布局（字段 / 值）比四列更能容纳长地址，打印时也更清晰。
        XWPFTable table = doc.createTable(8, 2);
        styleInfoTable(table, 2100, 6300);
        setCell(table, 0, 0, "广告牌编号", true);
        setCell(table, 0, 1, "{{广告牌编号}}", false);
        setCell(table, 1, 0, "广告牌名称", true);
        setCell(table, 1, 1, "{{广告牌名称}}", false);
        setCell(table, 2, 0, "所在公路", true);
        setCell(table, 2, 1, "{{广告牌公路}}", false);
        setCell(table, 3, 0, "路段位置", true);
        setCell(table, 3, 1, "{{广告牌路段}}", false);
        setCell(table, 4, 0, "详细地址", true);
        setCell(table, 4, 1, "{{广告牌地址}}", false);
        setCell(table, 5, 0, "尺寸", true);
        setCell(table, 5, 1, "{{广告牌尺寸}}", false);
        setCell(table, 6, 0, "材质", true);
        setCell(table, 6, 1, "{{广告牌材质}}", false);
        setCell(table, 7, 0, "可视面积", true);
        setCell(table, 7, 1, "{{广告牌可视面积}}", false);

        XWPFTable priceTable = doc.createTable(3, 2);
        styleInfoTable(priceTable, 2100, 6300);
        setCell(priceTable, 0, 0, "租赁单价", true);
        setCell(priceTable, 0, 1, "{{广告牌单价}}", false);
        setCell(priceTable, 1, 0, "计费周期", true);
        setCell(priceTable, 1, 1, "{{广告牌计费单位}}", false);
        setCell(priceTable, 2, 0, "报价说明", true);
        setCell(priceTable, 2, 1, "{{广告牌报价说明}}", false);

        XWPFParagraph spacer = doc.createParagraph();
        spacer.setSpacingAfter(80);
    }

    /** 表格宽度、列宽与细边框，避免 Word 默认等宽导致长地址挤压。 */
    private static void styleInfoTable(XWPFTable table, int labelWidth, int valueWidth) {
        CTTblPr tblPr = table.getCTTbl().getTblPr();
        if (tblPr == null) {
            tblPr = table.getCTTbl().addNewTblPr();
        }
        CTTblWidth tableWidth = tblPr.isSetTblW() ? tblPr.getTblW() : tblPr.addNewTblW();
        tableWidth.setType(STTblWidth.DXA);
        tableWidth.setW(BigInteger.valueOf(labelWidth + valueWidth));

        CTTblBorders borders = tblPr.isSetTblBorders()
                ? tblPr.getTblBorders() : tblPr.addNewTblBorders();
        applyBorder(borders.isSetTop() ? borders.getTop() : borders.addNewTop());
        applyBorder(borders.isSetBottom() ? borders.getBottom() : borders.addNewBottom());
        applyBorder(borders.isSetLeft() ? borders.getLeft() : borders.addNewLeft());
        applyBorder(borders.isSetRight() ? borders.getRight() : borders.addNewRight());
        applyBorder(borders.isSetInsideH() ? borders.getInsideH() : borders.addNewInsideH());
        applyBorder(borders.isSetInsideV() ? borders.getInsideV() : borders.addNewInsideV());

        for (XWPFTableRow row : table.getRows()) {
            setCellWidth(row.getCell(0), labelWidth);
            setCellWidth(row.getCell(1), valueWidth);
        }
    }

    private static void applyBorder(CTBorder border) {
        border.setVal(STBorder.SINGLE);
        border.setSz(BigInteger.valueOf(4));
        border.setColor("000000");
        border.setSpace(BigInteger.ZERO);
    }

    private static void setCellWidth(XWPFTableCell cell, int width) {
        CTTcPr tcPr = cell.getCTTc().isSetTcPr()
                ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        CTTblWidth cellWidth = tcPr.isSetTcW() ? tcPr.getTcW() : tcPr.addNewTcW();
        cellWidth.setType(STTblWidth.DXA);
        cellWidth.setW(BigInteger.valueOf(width));
    }

    private static void createSignatureTable(XWPFDocument doc) {
        XWPFTable table = doc.createTable(1, 2);
        table.setStyleID("TableGrid");

        XWPFTableCell left = table.getRow(0).getCell(0);
        XWPFTableCell right = table.getRow(0).getCell(1);

        signatureLine(left, "甲方（盖章）：", false);
        signatureLine(left, "{{@甲方签章}}", false);
        signatureLine(left, "法定代表人/授权代表：{{甲方法定代表人}}", false);
        signatureLine(left, "签署日期：{{甲方签署日期}}", false);

        signatureLine(right, "乙方（签字）：", false);
        signatureLine(right, "{{@乙方签名}}", false);
        signatureLine(right, "签署人：{{乙方签署人}}", false);
        signatureLine(right, "签署日期：{{乙方签署日期}}", false);
    }

    private static void signatureLine(XWPFTableCell cell, String text, boolean bold) {
        XWPFParagraph p = cell.addParagraph();
        p.setSpacingAfter(120);
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setBold(bold);
        run.setFontSize(12);
        setChineseFont(run);
    }

    private static void setCell(XWPFTable table, int row, int col, String text, boolean bold) {
        XWPFTableCell cell = table.getRow(row).getCell(col);
        XWPFParagraph p = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().get(0);
        p.setSpacingAfter(40);
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setBold(bold);
        run.setFontSize(11);
        setChineseFont(run);
    }

    private static void section(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(160);
        p.setSpacingAfter(100);
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(14);
        setChineseFont(run);
    }

    private static void paragraph(XWPFDocument doc, String text, boolean bold, int spacingAfter) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingAfter(spacingAfter);
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setBold(bold);
        run.setFontSize(12);
        setChineseFont(run);
    }

    private static void setChineseFont(XWPFRun run) {
        run.setFontFamily("宋体");
        run.setFontFamily("宋体", XWPFRun.FontCharRange.eastAsia);
    }

    private static void applyPageMargins(XWPFDocument doc) {
        CTSectPr sectPr = doc.getDocument().getBody().isSetSectPr()
                ? doc.getDocument().getBody().getSectPr()
                : doc.getDocument().getBody().addNewSectPr();
        CTPageMar margins = sectPr.isSetPgMar() ? sectPr.getPgMar() : sectPr.addNewPgMar();
        margins.setTop(BigInteger.valueOf(1440));
        margins.setBottom(BigInteger.valueOf(1440));
        margins.setLeft(BigInteger.valueOf(1700));
        margins.setRight(BigInteger.valueOf(1700));
    }
}
