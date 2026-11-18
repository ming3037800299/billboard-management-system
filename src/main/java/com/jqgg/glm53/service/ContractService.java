package com.jqgg.glm53.service;

import com.jqgg.glm53.config.AppProperties;
import com.jqgg.glm53.dto.ContractDetailVO;
import com.jqgg.glm53.dto.ContractEventVO;
import com.jqgg.glm53.dto.ContractFileVO;
import com.jqgg.glm53.dto.ContractSnapshot;
import com.jqgg.glm53.dto.ContractTemplateVO;
import com.jqgg.glm53.dto.ContractVO;
import com.jqgg.glm53.dto.SignForm;
import com.jqgg.glm53.dto.SignInfoVO;
import com.jqgg.glm53.dto.UploadByTokenForm;
import com.jqgg.glm53.dto.UploadSignedForm;
import com.jqgg.glm53.entity.ActorType;
import com.jqgg.glm53.entity.Contract;
import com.jqgg.glm53.entity.ContractEvent;
import com.jqgg.glm53.entity.ContractFile;
import com.jqgg.glm53.entity.ContractFileType;
import com.jqgg.glm53.entity.ContractSettings;
import com.jqgg.glm53.entity.ContractSource;
import com.jqgg.glm53.entity.ContractStatus;
import com.jqgg.glm53.entity.ContractTemplate;
import com.jqgg.glm53.entity.Reservation;
import com.jqgg.glm53.entity.ReservationStatus;
import com.jqgg.glm53.exception.BadRequestException;
import com.jqgg.glm53.exception.NotFoundException;
import com.jqgg.glm53.repository.ContractEventRepository;
import com.jqgg.glm53.repository.ContractFileRepository;
import com.jqgg.glm53.repository.ContractRepository;
import com.jqgg.glm53.repository.ContractTemplateRepository;
import com.jqgg.glm53.repository.ReservationRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import tools.jackson.databind.ObjectMapper;

/**
 * 合同业务服务：生成、签署、回签上传、作废、完成与下载。
 */
@Service
public class ContractService {

    private static final Logger log = LoggerFactory.getLogger(ContractService.class);

    private static final Set<ContractStatus> OPEN_STATUSES =
            EnumSet.of(ContractStatus.PENDING_SIGN, ContractStatus.SIGNED);

    private static final DateTimeFormatter DATE_CN =
            DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA);

    private static final DateTimeFormatter MONTH_DIR =
            DateTimeFormatter.ofPattern("yyyyMM");

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ContractRepository repository;
    private final ContractFileRepository fileRepository;
    private final ContractEventRepository eventRepository;
    private final ContractTemplateRepository templateRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationService reservationService;
    private final ContractSettingsService settingsService;
    private final ContractTemplateService templateService;
    private final ContractFileStorageService storage;
    private final DocxContractService docxService;
    private final AppProperties appProperties;
    private final SequenceService sequenceService;
    private final EntityManager entityManager;

    public ContractService(ContractRepository repository,
                           ContractFileRepository fileRepository,
                           ContractEventRepository eventRepository,
                           ContractTemplateRepository templateRepository,
                           ReservationRepository reservationRepository,
                           ReservationService reservationService,
                           ContractSettingsService settingsService,
                           ContractTemplateService templateService,
                           ContractFileStorageService storage,
                           DocxContractService docxService,
                           AppProperties appProperties,
                           SequenceService sequenceService,
                           EntityManager entityManager) {
        this.repository = repository;
        this.fileRepository = fileRepository;
        this.eventRepository = eventRepository;
        this.templateRepository = templateRepository;
        this.reservationRepository = reservationRepository;
        this.reservationService = reservationService;
        this.settingsService = settingsService;
        this.templateService = templateService;
        this.storage = storage;
        this.docxService = docxService;
        this.appProperties = appProperties;
        this.sequenceService = sequenceService;
        this.entityManager = entityManager;
    }

    // ------------------- 查询 -------------------

    /** 合同列表。 */
    @Transactional(readOnly = true)
    public List<ContractVO> list(String status, String keyword) {
        List<Contract> contracts = repository.findAllByOrderByIdDesc();
        Map<Long, Reservation> reservations = reservationRepository
                .findAllById(contracts.stream().map(Contract::getReservationId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Reservation::getId, Function.identity(), (a, b) -> a));
        Map<Long, String> templateNames = templateRepository.findAll().stream()
                .collect(Collectors.toMap(ContractTemplate::getId, ContractTemplate::getName, (a, b) -> a));

        String statusFilter = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        String kw = keyword == null ? "" : keyword.trim();
        List<ContractVO> result = new ArrayList<>();
        for (Contract contract : contracts) {
            if (!statusFilter.isEmpty() && !statusFilter.equals(contract.getStatus().name())) {
                continue;
            }
            Reservation reservation = reservations.get(contract.getReservationId());
            if (!kw.isEmpty() && !matchesKeyword(contract, reservation, kw)) {
                continue;
            }
            result.add(toVO(contract, reservation,
                    contract.getTemplateId() == null ? null : templateNames.get(contract.getTemplateId())));
        }
        return result;
    }

    /** 合同详情。 */
    @Transactional
    public ContractDetailVO detail(Long id) {
        Contract contract = requireContract(id);
        Reservation reservation = reservationRepository.findById(contract.getReservationId()).orElse(null);
        List<ContractFileVO> files = fileRepository.findByContractIdOrderByCreatedAtAsc(id).stream()
                .map(this::toFileVO)
                .toList();
        List<ContractEventVO> events = eventRepository.findByContractIdOrderByCreatedAtDesc(id).stream()
                .map(this::toEventVO)
                .toList();
        List<ContractTemplateVO> templates = templateService.enabledTemplates();
        return new ContractDetailVO(
                toVO(contract, reservation, templateNameOf(contract)),
                reservation == null ? null : reservationService.toVO(reservation, contract),
                files,
                events,
                templates);
    }

    // ------------------- 生成 -------------------

    /** 为预定生成合同草稿，并生成乙方签署链接。 */
    @Transactional
    public ContractDetailVO generate(Long reservationId, Long templateId) {
        Reservation preview = reservationService.requireReservation(reservationId);
        // 统一加锁顺序：广告牌 → 预定 → 合同；锁住预定后再判断是否已有有效合同，
        // 避免两个并发请求各自生成一份草稿。
        reservationService.lockBillboard(preview.getBillboardId());
        Reservation reservation = reservationService.requireReservationForUpdate(reservationId);
        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new BadRequestException("当前预定状态为「" + reservation.getStatus().label()
                    + "」，不能生成合同");
        }
        Contract open = reservationService.findOpenContract(reservationId);
        if (open != null) {
            throw new BadRequestException("该预定已有有效合同（" + open.getContractNo()
                    + "），请先作废后再重新生成");
        }
        settingsService.requireReadyForContract();
        ContractSettings settings = settingsService.getOrCreate();
        ContractTemplate template = templateService.resolveTemplate(templateId);

        Contract contract = new Contract();
        contract.setContractNo(nextContractNo());
        contract.setReservationId(reservationId);
        contract.setBillboardId(reservation.getBillboardId());
        contract.setTemplateId(template.getId());
        contract.setSource(ContractSource.GENERATED);
        contract.setStatus(ContractStatus.PENDING_SIGN);
        contract.setVersion(1);
        contract.setSignToken(newToken());
        contract.setSignTokenExpiresAt(LocalDateTime.now().plusDays(settingsService.signLinkTtlDays()));
        Contract saved = repository.save(contract);

        String subdir = contractSubdir(saved.getContractNo());
        String templateCopy = templateService.copyTemplateToContract(template, saved.getContractNo());
        String sealExt = storage.extensionOf(settings.getSealPath());
        String sealCopy = storage.copy(settings.getSealPath(), subdir,
                "seal." + (sealExt.isBlank() ? "png" : sealExt));

        Map<String, String> fields = buildFields(saved, reservation, settings);
        ContractSnapshot snapshot = new ContractSnapshot(fields, sealCopy, templateCopy, template.getName());
        saved.setDataSnapshot(writeSnapshot(snapshot));

        Path draftPath = storage.resolve(subdir + "/contract-draft.docx");
        byte[] sealBytes = storage.read(sealCopy);
        docxService.render(storage.resolve(templateCopy), draftPath, fields, sealBytes, null);
        cleanupFilesOnRollback(List.of(storage.resolve(templateCopy), storage.resolve(sealCopy), draftPath));

        saved.setCurrentFilePath(subdir + "/contract-draft.docx");
        saved.setCurrentFileName(saved.getContractNo() + "-待签署.docx");
        repository.save(saved);

        saveFile(saved.getId(), ContractFileType.TEMPLATE_COPY, templateCopy,
                saved.getContractNo() + "-模板副本.docx");
        saveFile(saved.getId(), ContractFileType.GENERATED, saved.getCurrentFilePath(),
                saved.getCurrentFileName());
        addEvent(saved.getId(), reservationId, "CONTRACT_GENERATED", ActorType.ADMIN,
                "按模板「" + template.getName() + "」生成合同 " + saved.getContractNo());
        return detail(saved.getId());
    }

    // ------------------- 签署链接与作废 -------------------

    /** 重新生成乙方签署链接（旧链接立即失效）。 */
    @Transactional
    public ContractDetailVO refreshLink(Long id) {
        Contract contract = requireContractForUpdate(id);
        if (contract.getStatus() != ContractStatus.PENDING_SIGN) {
            throw new BadRequestException("只有「待签署」合同可以刷新签署链接");
        }
        contract.setSignToken(newToken());
        contract.setSignTokenExpiresAt(LocalDateTime.now().plusDays(settingsService.signLinkTtlDays()));
        contract.setSignTokenRevoked(false);
        repository.save(contract);
        addEvent(id, contract.getReservationId(), "TOKEN_REFRESHED", ActorType.ADMIN,
                "刷新乙方签署链接");
        return detail(id);
    }

    /** 作废合同。 */
    @Transactional
    public ContractDetailVO voidContract(Long id, String reason) {
        Contract contract = requireContractForUpdate(id);
        if (contract.getStatus() == ContractStatus.VOID) {
            throw new BadRequestException("合同已作废，无需重复操作");
        }
        if (contract.getStatus() == ContractStatus.COMPLETED) {
            throw new BadRequestException("合同已完成，不能作废");
        }
        ContractStatus previous = contract.getStatus();
        contract.setStatus(ContractStatus.VOID);
        contract.setVoidReason(safeReason(reason));
        contract.setSignTokenRevoked(true);
        repository.save(contract);

        if (previous == ContractStatus.SIGNED) {
            reservationRepository.findById(contract.getReservationId()).ifPresent(r ->
                    reservationService.cancelFromContract(r, "合同作废：" + safeReason(reason)));
        }
        addEvent(id, contract.getReservationId(), "CONTRACT_VOIDED", ActorType.ADMIN,
                "作废合同：" + safeReason(reason));
        return detail(id);
    }

    /** 提前结束合同，释放广告牌。 */
    @Transactional
    public ContractDetailVO complete(Long id) {
        Contract contract = requireContractForUpdate(id);
        if (contract.getStatus() != ContractStatus.SIGNED) {
            throw new BadRequestException("只有「已签署」合同可以结束");
        }
        contract.setStatus(ContractStatus.COMPLETED);
        contract.setCompletedAt(LocalDateTime.now());
        repository.save(contract);
        reservationRepository.findById(contract.getReservationId()).ifPresent(r -> {
            if (r.getStatus() == ReservationStatus.SIGNED) {
                reservationService.markCompleted(r);
            }
        });
        addEvent(id, contract.getReservationId(), "CONTRACT_COMPLETED", ActorType.ADMIN,
                "管理员提前结束合同");
        return detail(id);
    }

    // ------------------- 线下已签合同上传 -------------------

    /** 后台上传线下已签合同。 */
    @Transactional
    public ContractDetailVO uploadSignedForReservation(Long reservationId, UploadSignedForm form) {
        if (form == null || form.getFile() == null || form.getFile().isEmpty()) {
            throw new BadRequestException("请选择线下已签合同文件");
        }
        Reservation preview = reservationService.requireReservation(reservationId);
        reservationService.lockBillboard(preview.getBillboardId());
        Reservation reservation = reservationService.requireReservationForUpdate(reservationId);
        if (reservation.getStatus() == ReservationStatus.SIGNED) {
            throw new BadRequestException("该预定已签约，无需重复上传");
        }
        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new BadRequestException("当前预定状态为「" + reservation.getStatus().label()
                    + "」，不能上传已签合同");
        }
        Contract contract = reservationService.findOpenContract(reservationId);
        if (contract != null && contract.getStatus() == ContractStatus.SIGNED) {
            throw new BadRequestException("该预定已有已签署合同");
        }
        if (contract == null) {
            contract = new Contract();
            contract.setContractNo(nextContractNo());
            contract.setReservationId(reservationId);
            contract.setBillboardId(reservation.getBillboardId());
            contract.setSource(ContractSource.UPLOADED);
            contract.setStatus(ContractStatus.SIGNED);
            contract.setVersion(1);
            contract = repository.save(contract);
        }
        applyUploadedFile(contract, form, null);
        Contract saved = repository.save(contract);
        reservationService.markSigned(reservation);
        addEvent(saved.getId(), reservationId, "CONTRACT_UPLOADED", ActorType.ADMIN,
                "后台上传线下已签合同");
        return detail(saved.getId());
    }

    // ------------------- 乙方签署链接 -------------------

    /** 乙方签署页公开信息。 */
    @Transactional
    public SignInfoVO publicInfo(String token) {
        Contract contract = requireReadableToken(token);
        Reservation reservation = reservationRepository.findById(contract.getReservationId())
                .orElseThrow(() -> new NotFoundException("预定记录不存在"));
        ContractSettings settings = settingsService.getOrCreate();

        boolean expired = contract.getSignTokenExpiresAt() != null
                && contract.getSignTokenExpiresAt().isBefore(LocalDateTime.now());
        boolean canSign = contract.getStatus() == ContractStatus.PENDING_SIGN
                && !contract.isSignTokenRevoked()
                && !expired;
        String blockedReason;
        if (contract.getStatus() == ContractStatus.SIGNED) {
            blockedReason = "合同已签署，无需重复签署";
        } else if (contract.getStatus() == ContractStatus.COMPLETED) {
            blockedReason = "合同已完成";
        } else if (contract.getStatus() == ContractStatus.VOID) {
            blockedReason = "合同已作废";
        } else if (contract.isSignTokenRevoked()) {
            blockedReason = "签署链接已失效，请联系甲方重新获取";
        } else if (expired) {
            blockedReason = "签署链接已过期，请联系甲方重新获取";
        } else {
            blockedReason = null;
        }

        return new SignInfoVO(
                contract.getContractNo(),
                contract.getStatus().name(),
                contract.getStatus().label(),
                canSign,
                blockedReason,
                reservation.getBillboardName(),
                reservation.getBillboardCode(),
                reservation.getBillboardRoad(),
                reservation.getBillboardSection(),
                reservation.getTenantName(),
                reservation.getTenantType().label(),
                reservation.getTenantContact(),
                reservation.getStartDate(),
                reservation.getEndDate(),
                reservation.getTotalAmount(),
                settings.getPartyAName(),
                contract.getSignerName(),
                contract.getSignedAt(),
                contract.getSignTokenExpiresAt(),
                contract.getCurrentFileName(),
                "/api/sign/" + token + "/file",
                "/api/sign/" + token + "/file",
                appProperties.getSiteName());
    }

    /** 乙方在线手写签名 / 上传签名图片。 */
    @Transactional
    public SignInfoVO sign(String token, SignForm form, String ip) {
        Contract contract = requireSignable(token);
        if (form == null || form.getSignature() == null || form.getSignature().isEmpty()) {
            throw new BadRequestException("请先完成手写签名或上传签名图片");
        }
        requireConsent(form.getConsent());
        String signerName = requireText(form.getSignerName(), "请填写签署人姓名");

        ContractSnapshot snapshot = parseSnapshot(contract.getDataSnapshot());
        if (snapshot == null || snapshot.fields() == null
                || snapshot.templatePath() == null || snapshot.sealPath() == null) {
            throw new BadRequestException("合同数据快照缺失，无法完成签署，请联系甲方重新生成合同");
        }
        Path templatePath = storage.resolve(snapshot.templatePath());
        if (!Files.exists(templatePath)) {
            throw new BadRequestException("合同模板副本缺失，请联系甲方重新生成合同");
        }

        String subdir = contractDirOf(contract);
        String signaturePath = storage.storeImage(form.getSignature(), subdir);
        saveFile(contract.getId(), ContractFileType.SIGNATURE, signaturePath,
                contract.getContractNo() + "-乙方签名");
        byte[] signatureBytes = storage.read(signaturePath);

        Map<String, String> fields = new LinkedHashMap<>(snapshot.fields());
        fields.put("乙方签署人", signerName);
        fields.put("乙方签署日期", LocalDate.now().format(DATE_CN));
        Path finalPath = storage.resolve(subdir + "/contract-signed.docx");
        docxService.render(templatePath, finalPath, fields,
                storage.read(snapshot.sealPath()), signatureBytes);
        cleanupFilesOnRollback(List.of(storage.resolve(signaturePath), finalPath));

        Long contractId = contract.getId();
        Long reservationId = contract.getReservationId();
        String currentPath = subdir + "/contract-signed.docx";
        String currentName = contract.getContractNo() + "-已签署.docx";
        LocalDateTime signedAt = LocalDateTime.now();
        int updated = repository.markSignedIfPending(contractId,
                ContractStatus.PENDING_SIGN, ContractStatus.SIGNED,
                signaturePath, currentPath, currentName, signerName, signedAt, ip);
        if (updated == 0) {
            // 另一个并发请求已经先完成签署；本次写入的文件由事务回滚清理
            throw new BadRequestException("合同已被其他请求签署，请刷新后查看");
        }
        // 清除一级缓存，后续事件 / 响应都从数据库读取最新状态
        entityManager.clear();

        saveFile(contractId, ContractFileType.FINAL, currentPath, currentName);
        reservationRepository.findById(reservationId).ifPresent(reservationService::markSigned);
        addEvent(contractId, reservationId, "CONTRACT_SIGNED", ActorType.TENANT,
                "乙方在线签署，签署人：" + signerName);
        return publicInfo(token);
    }

    /** 乙方通过签署链接上传线下已签合同。 */
    @Transactional
    public SignInfoVO uploadSigned(String token, UploadByTokenForm form, String ip) {
        Contract contract = requireSignable(token);
        if (form == null || form.getFile() == null || form.getFile().isEmpty()) {
            throw new BadRequestException("请选择已签署的合同文件");
        }
        requireConsent(form.getConsent());
        String signerName = form.getSignerName() == null || form.getSignerName().isBlank()
                ? null : form.getSignerName().trim();
        Reservation reservation = reservationRepository.findById(contract.getReservationId())
                .orElseThrow(() -> new NotFoundException("预定记录不存在"));

        UploadSignedForm upload = new UploadSignedForm();
        upload.setFile(form.getFile());
        upload.setSignerName(signerName == null ? reservation.getTenantName() : signerName);
        applyUploadedFile(contract, upload, ip);
        repository.save(contract);
        reservationService.markSigned(reservation);
        addEvent(contract.getId(), reservation.getId(), "CONTRACT_UPLOADED", ActorType.TENANT,
                "乙方通过签署链接上传线下已签合同");
        return publicInfo(token);
    }

    /** 乙方签署页下载当前合同文件。 */
    @Transactional
    public FileDownload publicFile(String token) {
        Contract contract = requireReadableToken(token);
        return currentDownload(contract);
    }

    /** 管理员下载当前合同文件。 */
    @Transactional
    public FileDownload adminCurrentFile(Long id) {
        return currentDownload(requireContract(id));
    }

    /** 管理员下载指定合同文件版本。 */
    @Transactional
    public FileDownload adminFile(Long contractId, Long fileId) {
        ContractFile file = fileRepository.findById(fileId)
                .orElseThrow(() -> new NotFoundException("合同文件不存在"));
        if (!file.getContractId().equals(contractId)) {
            throw new NotFoundException("合同文件不存在");
        }
        return downloadOf(file.getFilePath(), file.getOriginalName());
    }

    /** 结束日期已过的合同自动完成（定时任务调用）。 */
    @Transactional
    public int completeExpiredContracts() {
        LocalDate today = LocalDate.now();
        List<Contract> candidates = repository.findExpiredSignedContracts(ContractStatus.SIGNED, today);
        int completed = 0;
        for (Contract candidate : candidates) {
            try {
                // 多节点 / 重复调度时，先按统一顺序锁行并重新校验状态；
                // 已经被其他节点处理过的合同会在这里被跳过，避免重复完成、重复释放广告牌。
                reservationService.lockBillboard(candidate.getBillboardId());
                Reservation reservation = reservationService
                        .requireReservationForUpdate(candidate.getReservationId());
                Contract contract = repository.findByIdForUpdate(candidate.getId()).orElse(null);
                if (contract == null) {
                    continue;
                }
                entityManager.refresh(contract);
                if (contract.getStatus() != ContractStatus.SIGNED) {
                    continue;
                }
                if (reservation.getEndDate() == null || !reservation.getEndDate().isBefore(today)
                        || reservation.getStatus() != ReservationStatus.SIGNED) {
                    continue;
                }
                contract.setStatus(ContractStatus.COMPLETED);
                contract.setCompletedAt(LocalDateTime.now());
                repository.save(contract);
                reservationService.markCompleted(reservation);
                addEvent(contract.getId(), contract.getReservationId(), "CONTRACT_COMPLETED", ActorType.SYSTEM,
                        "合同到期，系统自动完成并释放广告牌");
                completed++;
            } catch (Exception e) {
                // 单份合同数据异常不应阻断其余到期合同
                log.warn("到期合同处理失败：contractId={}", candidate.getId(), e);
            }
        }
        return completed;
    }

    // ------------------- 内部实现 -------------------

    private void applyUploadedFile(Contract contract, UploadSignedForm form, String ip) {
        Reservation reservation = reservationRepository.findById(contract.getReservationId())
                .orElseThrow(() -> new NotFoundException("预定记录不存在"));
        String subdir = contract.getCurrentFilePath() != null
                ? contractDirOf(contract) : contractSubdir(contract.getContractNo());
        String filePath = storage.storeSignedContract(form.getFile(), subdir);
        cleanupFilesOnRollback(List.of(storage.resolve(filePath)));
        String ext = storage.extensionOf(filePath);
        String originalName = storage.originalName(form.getFile(),
                contract.getContractNo() + "-已签." + (ext.isBlank() ? "pdf" : ext));

        contract.setSource(ContractSource.UPLOADED);
        contract.setStatus(ContractStatus.SIGNED);
        contract.setCurrentFilePath(filePath);
        contract.setCurrentFileName(originalName);
        contract.setSignerName(form.getSignerName() == null || form.getSignerName().isBlank()
                ? reservation.getTenantName() : form.getSignerName().trim());
        contract.setSignedAt(LocalDateTime.now());
        if (ip != null) {
            contract.setSignerIp(ip);
        }
        saveFile(contract.getId(), ContractFileType.UPLOADED, filePath, originalName);
    }

    private Contract requireSignable(String token) {
        // 先做无锁预读拿到广告牌 / 预定，再按“广告牌 → 预定 → 合同”的统一顺序加锁；
        // 第二个并发签署请求会在合同行锁上等待，随后看到状态已变为 SIGNED 而失败。
        Contract preview = findByToken(token);
        reservationService.lockBillboard(preview.getBillboardId());
        reservationService.requireReservationForUpdate(preview.getReservationId());
        Contract contract = repository.findBySignTokenForUpdate(token.trim())
                .orElseThrow(() -> new NotFoundException("签署链接无效或合同不存在"));
        // 预读可能已把旧状态放进一级缓存，refresh 后再判断，避免并发双签
        entityManager.refresh(contract);
        if (contract.isSignTokenRevoked()) {
            throw new BadRequestException("签署链接已失效，请联系甲方重新获取");
        }
        if (contract.getSignTokenExpiresAt() != null
                && contract.getSignTokenExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BadRequestException("签署链接已过期，请联系甲方重新获取");
        }
        if (contract.getStatus() != ContractStatus.PENDING_SIGN) {
            throw new BadRequestException("合同当前状态为「" + contract.getStatus().label() + "」，不能签署");
        }
        return contract;
    }

    private Contract findByToken(String token) {
        if (token == null || token.isBlank()) {
            throw new NotFoundException("签署链接无效");
        }
        return repository.findBySignToken(token.trim())
                .orElseThrow(() -> new NotFoundException("签署链接无效或合同不存在"));
    }

    /** 签署链接只读访问（查看 / 下载）同样校验撤销与过期，避免过期链接继续泄露合同文件。 */
    private Contract requireReadableToken(String token) {
        Contract contract = findByToken(token);
        if (contract.isSignTokenRevoked()) {
            throw new BadRequestException("签署链接已失效，请联系甲方重新获取");
        }
        if (contract.getSignTokenExpiresAt() != null
                && contract.getSignTokenExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BadRequestException("签署链接已过期，请联系甲方重新获取");
        }
        return contract;
    }

    private Contract requireContract(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("合同不存在（ID=" + id + "）"));
    }

    /**
     * 按“广告牌 → 预定 → 合同”的统一顺序锁定合同及其关联记录。
     * 管理员作废 / 结束 / 刷新链接与乙方签署共用同一顺序，避免交叉死锁。
     */
    private Contract requireContractForUpdate(Long id) {
        Contract preview = requireContract(id);
        reservationService.lockBillboard(preview.getBillboardId());
        reservationService.requireReservationForUpdate(preview.getReservationId());
        Contract contract = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("合同不存在（ID=" + id + "）"));
        entityManager.refresh(contract);
        return contract;
    }

    /** 事务回滚时删除本次已写出的合同文件，避免库里回滚但磁盘残留孤儿文件。 */
    private void cleanupFilesOnRollback(List<Path> paths) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    return;
                }
                for (Path path : paths) {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        log.warn("回滚清理合同文件失败：{}", path, e);
                    }
                }
            }
        });
    }

    private Map<String, String> buildFields(Contract contract, Reservation r, ContractSettings s) {
        LocalDate today = LocalDate.now();
        Map<String, String> m = new LinkedHashMap<>();
        m.put("合同编号", contract.getContractNo());
        m.put("签订地点", nvl(s.getPartyAAddress(), "甲方住所地"));
        m.put("签订日期", today.format(DATE_CN));
        m.put("当前日期", today.format(DATE_CN));

        m.put("甲方名称", nvl(s.getPartyAName(), ""));
        m.put("甲方统一社会信用代码", nvl(s.getPartyACreditCode(), ""));
        m.put("甲方地址", nvl(s.getPartyAAddress(), ""));
        m.put("甲方法定代表人", nvl(s.getPartyALegalRep(), ""));
        m.put("甲方联系人", nvl(s.getPartyAContact(), ""));
        m.put("甲方电话", nvl(s.getPartyAPhone(), ""));
        m.put("甲方开户行", nvl(s.getPartyABankName(), ""));
        m.put("甲方账号", nvl(s.getPartyABankAccount(), ""));
        m.put("甲方签署日期", today.format(DATE_CN));

        m.put("乙方名称", nvl(r.getTenantName(), ""));
        m.put("乙方类型", r.getTenantType().label());
        m.put("乙方证件号码", nvl(r.getTenantIdNumber(), ""));
        m.put("乙方地址", nvl(r.getTenantAddress(), ""));
        m.put("乙方联系人", nvl(r.getTenantContact(), r.getTenantName()));
        m.put("乙方签署人", nvl(r.getTenantContact(), r.getTenantName()));
        m.put("乙方电话", nvl(r.getTenantPhone(), ""));
        m.put("乙方邮箱", nvl(r.getTenantEmail(), ""));
        m.put("乙方签署日期", "");

        m.put("广告牌编号", nvl(r.getBillboardCode(), ""));
        m.put("广告牌名称", nvl(r.getBillboardName(), ""));
        m.put("广告牌公路", nvl(r.getBillboardRoad(), ""));
        m.put("广告牌路段", nvl(r.getBillboardSection(), ""));
        m.put("广告牌地址", nvl(r.getBillboardAddress(), ""));
        m.put("广告牌尺寸", nvl(r.getBillboardSize(), ""));
        m.put("广告牌材质", nvl(r.getBillboardMaterial(), ""));
        m.put("广告牌可视面积", nvl(r.getBillboardVisibleArea(), ""));
        m.put("广告牌单价", formatAmount(r.getBillboardPrice()));
        m.put("广告牌计费单位", nvl(r.getBillboardPriceUnit(), ""));
        m.put("广告牌报价说明", nvl(r.getBillboardPriceNote(), "无"));

        m.put("租赁开始日期", r.getStartDate() == null ? "" : r.getStartDate().format(DATE_CN));
        m.put("租赁结束日期", r.getEndDate() == null ? "" : r.getEndDate().format(DATE_CN));
        m.put("租赁时长", leaseDuration(r.getLeaseMonths()));
        m.put("合同金额", formatAmount(r.getTotalAmount()));
        m.put("合同金额大写", ContractAmountFormatter.toChinese(r.getTotalAmount()));
        m.put("定金金额", formatAmount(r.getDepositAmount()));
        m.put("付款方式", nvl(r.getPaymentTerms(), "一次性支付"));
        m.put("广告内容", nvl(r.getAdContent(), "以乙方提供并经甲方审核的广告画面为准"));
        m.put("特殊约定", nvl(r.getSpecialTerms(), "无"));
        m.put("备注", nvl(r.getRemark(), "无"));
        return m;
    }

    private FileDownload currentDownload(Contract contract) {
        if (contract.getCurrentFilePath() == null || !storage.exists(contract.getCurrentFilePath())) {
            throw new NotFoundException("合同文件不存在");
        }
        String name = contract.getCurrentFileName() == null
                ? contract.getContractNo() + ".docx" : contract.getCurrentFileName();
        return downloadOf(contract.getCurrentFilePath(), name);
    }

    private FileDownload downloadOf(String relativePath, String filename) {
        Path path = storage.resolve(relativePath);
        if (!Files.exists(path)) {
            throw new NotFoundException("合同文件不存在");
        }
        String ext = storage.extensionOf(relativePath);
        String contentType = switch (ext) {
            case "pdf" -> "application/pdf";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            default -> "application/octet-stream";
        };
        return new FileDownload(path, filename == null ? path.getFileName().toString() : filename, contentType);
    }

    private ContractVO toVO(Contract c, Reservation r, String templateName) {
        String signingPath = c.getSignToken() == null || c.isSignTokenRevoked()
                ? null : "/sign/" + c.getSignToken();
        return new ContractVO(
                c.getId(),
                c.getContractNo(),
                c.getReservationId(),
                c.getBillboardId(),
                r == null ? null : r.getBillboardCode(),
                r == null ? null : r.getBillboardName(),
                r == null ? null : r.getBillboardRoad(),
                r == null ? null : r.getBillboardSection(),
                r == null ? null : r.getTenantType().name(),
                r == null ? null : r.getTenantType().label(),
                r == null ? null : r.getTenantName(),
                r == null ? null : r.getTenantPhone(),
                r == null ? null : r.getStartDate(),
                r == null ? null : r.getEndDate(),
                r == null ? null : r.getTotalAmount(),
                templateName,
                c.getSource().name(),
                c.getSource().label(),
                c.getStatus().name(),
                c.getStatus().label(),
                c.getCurrentFileName(),
                c.getSignedAt(),
                c.getSignerName(),
                signingPath,
                c.getSignTokenExpiresAt(),
                c.isSignTokenRevoked(),
                c.getVoidReason(),
                c.getVersion(),
                c.getCreatedAt(),
                c.getUpdatedAt(),
                c.getCompletedAt());
    }

    private ContractFileVO toFileVO(ContractFile file) {
        return new ContractFileVO(
                file.getId(),
                file.getFileType().name(),
                file.getOriginalName(),
                file.getFileSize(),
                "/api/admin/contracts/" + file.getContractId() + "/files/" + file.getId(),
                file.getCreatedAt());
    }

    private ContractEventVO toEventVO(ContractEvent event) {
        return new ContractEventVO(
                event.getId(),
                event.getAction(),
                event.getActorType().name(),
                event.getDetail(),
                event.getCreatedAt());
    }

    private String templateNameOf(Contract contract) {
        if (contract.getTemplateId() != null) {
            return templateRepository.findById(contract.getTemplateId())
                    .map(ContractTemplate::getName)
                    .orElseGet(() -> snapshotTemplateName(contract));
        }
        return snapshotTemplateName(contract);
    }

    private String snapshotTemplateName(Contract contract) {
        ContractSnapshot snapshot = parseSnapshot(contract.getDataSnapshot());
        return snapshot == null ? null : snapshot.templateName();
    }

    private ContractSnapshot parseSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, ContractSnapshot.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String writeSnapshot(ContractSnapshot snapshot) {
        try {
            return MAPPER.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("合同数据快照序列化失败", e);
        }
    }

    private void saveFile(Long contractId, ContractFileType type, String relativePath, String originalName) {
        ContractFile file = new ContractFile();
        file.setContractId(contractId);
        file.setFileType(type);
        file.setFilePath(relativePath);
        file.setOriginalName(originalName);
        file.setFileSize(storage.size(relativePath));
        fileRepository.save(file);
    }

    private void addEvent(Long contractId, Long reservationId, String action,
                          ActorType actor, String detail) {
        ContractEvent event = new ContractEvent();
        event.setContractId(contractId);
        event.setReservationId(reservationId);
        event.setAction(action);
        event.setActorType(actor);
        event.setDetail(detail);
        eventRepository.save(event);
    }

    private String nextContractNo() {
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        long seq = sequenceService.next("contract-" + day);
        return "HT-" + day + "-" + String.format("%04d", seq);
    }

    private String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String contractSubdir(String contractNo) {
        return "contracts/" + LocalDate.now().format(MONTH_DIR) + "/" + contractNo;
    }

    private String contractDirOf(Contract contract) {
        String path = contract.getCurrentFilePath();
        if (path != null) {
            int index = path.lastIndexOf('/');
            if (index > 0) {
                return path.substring(0, index);
            }
        }
        return contractSubdir(contract.getContractNo());
    }

    private boolean matchesKeyword(Contract contract, Reservation reservation, String keyword) {
        String lower = keyword.toLowerCase(Locale.ROOT);
        if (contains(contract.getContractNo(), lower)) {
            return true;
        }
        if (reservation == null) {
            return false;
        }
        return contains(reservation.getReservationNo(), lower)
                || contains(reservation.getTenantName(), lower)
                || contains(reservation.getBillboardName(), lower)
                || contains(reservation.getBillboardCode(), lower);
    }

    private boolean contains(String value, String lowerKeyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerKeyword);
    }

    private String formatAmount(BigDecimal amount) {
        return amount == null ? "0.00" : ContractAmountFormatter.toGrouped(amount);
    }

    private String leaseDuration(int months) {
        if (months >= 12 && months % 12 == 0) {
            return (months / 12) + "年";
        }
        return months + "个月";
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(message);
        }
        return value.trim();
    }

    private void requireConsent(String consent) {
        boolean accepted = consent != null && (consent.equalsIgnoreCase("true")
                || consent.equalsIgnoreCase("on")
                || consent.equals("1"));
        if (!accepted) {
            throw new BadRequestException("请勾选确认：本人已阅读并同意以电子签名方式签署本合同");
        }
    }

    private String safeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "未填写原因";
        }
        String value = reason.trim();
        return value.length() > 480 ? value.substring(0, 480) : value;
    }

    private String nvl(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 合同下载文件描述。 */
    public record FileDownload(Path path, String filename, String contentType) {
    }
}
