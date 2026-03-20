package com.example.promotion.service;

import com.example.promotion.dto.BundleItemDTO;
import com.example.promotion.dto.CartItemPriceRequest;
import com.example.promotion.dto.CartItemPriceResponse;
import com.example.promotion.dto.PromotionDTO;
import com.example.promotion.dto.PromotionTargetDTO;
import com.example.promotion.entity.BundleItem;
import com.example.promotion.entity.Promotion;
import com.example.promotion.entity.PromotionTarget;
import com.example.promotion.repository.PromotionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional
public class PromotionServiceImpl implements PromotionService {

    private static final Logger log = LoggerFactory.getLogger(PromotionServiceImpl.class);

    private final PromotionRepository promotionRepository;
    private final CacheManager cacheManager;
    private final RestTemplate restTemplate;

    @Value("${nifi.promotion.signal-url:}")
    private String nifiSignalUrl;

    public PromotionServiceImpl(
            PromotionRepository promotionRepository,
            CacheManager cacheManager,
            RestTemplateBuilder restTemplateBuilder
    ) {
        this.promotionRepository = promotionRepository;
        this.cacheManager = cacheManager;
        this.restTemplate = restTemplateBuilder.build();
    }

    /* ================== QUERY ================== */

    @Override
    public List<PromotionDTO> getAllPromotions() {
        return promotionRepository.findAll()
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<PromotionDTO> searchPromotions(String query, String discountType, String targetType, Long targetId, Boolean active) {
        Promotion.DiscountType parsedDiscountType = parseDiscountType(discountType);
        PromotionTarget.TargetType parsedTargetType = parseTargetType(targetType);
        String q = query != null && !query.isBlank() ? query.trim() : null;

        return promotionRepository.searchPromotions(q, parsedDiscountType, active, parsedTargetType, targetId)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public String generatePromotionCode(String name) {
        String prefix = buildPromoPrefixFromName(name);
        if (prefix == null || prefix.isBlank()) {
            prefix = "KM";
        }
        List<String> existing = promotionRepository.findCodesByPrefix(prefix);
        java.util.Set<String> used = existing == null
                ? java.util.Collections.emptySet()
                : existing.stream().map(code -> code.toUpperCase()).collect(Collectors.toSet());
        for (int i = 1; i < 1000; i++) {
            String suffix = String.format("%03d", i);
            String candidate = prefix + "-" + suffix;
            if (!used.contains(candidate.toUpperCase())) {
                return candidate;
            }
        }
        return prefix + "-" + String.format("%03d", (int) (Math.random() * 900 + 100));
    }

    @Override
    @Cacheable(cacheNames = "activePromotions", key = "'active'")
    public List<PromotionDTO> getActivePromotions() {
        return promotionRepository.findActivePromotions(LocalDateTime.now())
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Cacheable(cacheNames = "promotionByCode", key = "#code", unless = "#result == null")
    public PromotionDTO getPromotionByCode(String code) {
        return promotionRepository.findByCode(code)
                .map(this::toDTO)
                .orElse(null);
    }

    @Override
    @Cacheable(cacheNames = "activePromotionByCode", key = "#code", unless = "#result == null")
    public PromotionDTO getActivePromotionByCode(String code) {
        return promotionRepository.findActivePromotionByCode(code, LocalDateTime.now())
                .map(this::toDTO)
                .orElse(null);
    }

    /* ================== COMMAND ================== */

    @Override
    @CachePut(cacheNames = "promotionByCode", key = "#result.code", unless = "#result == null")
    @CacheEvict(cacheNames = "activePromotions", allEntries = true)
    public PromotionDTO createPromotion(PromotionDTO dto) {
        validatePromotionDTO(dto, null);
        Promotion promotion = new Promotion();
        promotion.setTargets(new ArrayList<>());
        promotion.setBundleItems(new ArrayList<>());

        applyDTOToEntity(dto, promotion);
        PromotionDTO created = toDTO(promotionRepository.save(promotion));
        refreshActivePromotionCache(created);
        emitNifiSignalAfterCommit("PROMOTION_CREATED", created);
        return created;
    }

    @Override
    @CachePut(cacheNames = "promotionByCode", key = "#result.code", unless = "#result == null")
    @CacheEvict(cacheNames = "activePromotions", allEntries = true)
    public PromotionDTO updatePromotion(Long id, PromotionDTO dto) {
        if (id == null) {
            throw new IllegalArgumentException("Promotion id must not be null");
        }
        validatePromotionDTO(dto, id);

        Promotion promotion = promotionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Promotion not found"));
        String previousCode = promotion.getCode();

        if (promotion.getTargets() == null) {
            promotion.setTargets(new ArrayList<>());
        } else {
            promotion.getTargets().clear();
        }

        if (promotion.getBundleItems() == null) {
            promotion.setBundleItems(new ArrayList<>());
        } else {
            promotion.getBundleItems().clear();
        }

        applyDTOToEntity(dto, promotion);
        PromotionDTO updated = toDTO(promotionRepository.save(promotion));
        if (updated != null && previousCode != null && !previousCode.equals(updated.getCode())) {
            evictPromotionByCode(previousCode);
            evictActivePromotionByCode(previousCode);
        }
        refreshActivePromotionCache(updated);
        emitNifiSignalAfterCommit("PROMOTION_UPDATED", updated);
        return updated;
    }

    @Override
    @CacheEvict(cacheNames = "activePromotions", allEntries = true)
    public void deletePromotion(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Promotion id must not be null");
        }

        Promotion promotion = promotionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Promotion not found"));
        PromotionDTO snapshot = toDTO(promotion);

        if (promotion != null) {
            promotionRepository.delete(promotion);
            evictPromotionByCode(promotion.getCode());
            evictActivePromotionByCode(promotion.getCode());
        }
        emitNifiSignalAfterCommit("PROMOTION_DELETED", snapshot);
    }

    @Override
    @CacheEvict(cacheNames = "activePromotions", allEntries = true)
    public void deactivatePromotion(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Promotion id must not be null");
        }

        Promotion promotion = promotionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Promotion not found"));

        promotion.setActive(false);
        promotionRepository.save(promotion);
        evictPromotionByCode(promotion.getCode());
        evictActivePromotionByCode(promotion.getCode());
    }

    @Override
    @CacheEvict(cacheNames = "activePromotions", allEntries = true)
    public void activatePromotion(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Promotion id must not be null");
        }

        Promotion promotion = promotionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Promotion not found"));

        promotion.setActive(true);
        promotionRepository.save(promotion);
        evictPromotionByCode(promotion.getCode());
        evictActivePromotionByCode(promotion.getCode());
    }

    /* ================== MAPPING ================== */

    private PromotionDTO toDTO(Promotion promotion) {
        PromotionDTO dto = new PromotionDTO();
        dto.setId(promotion.getId());
        dto.setCode(promotion.getCode());
        dto.setName(promotion.getName());
        dto.setDescription(promotion.getDescription());
        dto.setDiscountType(normalizeDiscountType(promotion));
        dto.setDiscountValue(promotion.getDiscountValue());
        dto.setStartDate(promotion.getStartDate());
        dto.setEndDate(promotion.getEndDate());
        dto.setActive(promotion.getActive());

        try {
            List<PromotionTarget> targets = promotion.getTargets();
            dto.setTargets(
                    targets == null
                            ? Collections.emptyList()
                            : targets.stream()
                                .map(this::toTargetDTO)
                                .collect(Collectors.toList())
            );
        } catch (RuntimeException ex) {
            dto.setTargets(Collections.emptyList());
        }

        try {
            List<BundleItem> bundleItems = promotion.getBundleItems();
            dto.setBundleItems(
                    bundleItems == null
                            ? Collections.emptyList()
                            : bundleItems.stream()
                                .map(this::toBundleItemDTO)
                                .collect(Collectors.toList())
            );
        } catch (RuntimeException ex) {
            dto.setBundleItems(Collections.emptyList());
        }

        return dto;
    }

    private PromotionTargetDTO toTargetDTO(PromotionTarget target) {
        PromotionTargetDTO dto = new PromotionTargetDTO();
        dto.setId(target.getId());
        dto.setTargetType(target.getTargetType());
        dto.setTargetId(target.getTargetId());
        return dto;
    }

    private BundleItemDTO toBundleItemDTO(BundleItem item) {
        BundleItemDTO dto = new BundleItemDTO();
        dto.setId(item.getId());
        dto.setMainProductId(item.getMainProductId());
        dto.setMainQuantity(item.getMainQuantity());
        dto.setGiftProductId(item.getGiftProductId());
        dto.setGiftQuantity(item.getGiftQuantity());
        dto.setGiftDiscountType(item.getGiftDiscountType());
        dto.setGiftDiscountValue(item.getGiftDiscountValue());
        dto.setStatus(item.getStatus());
        dto.setProductId(item.getMainProductId());
        dto.setQuantity(item.getMainQuantity());
        return dto;
    }

    private void applyDTOToEntity(PromotionDTO dto, Promotion promotion) {
        promotion.setCode(dto.getCode());
        promotion.setName(dto.getName());
        promotion.setDescription(dto.getDescription());
        if (dto.getDiscountType() != null) {
            promotion.setDiscountType(dto.getDiscountType());
            promotion.setPromotionType(mapPromotionTypeFromDiscount(dto.getDiscountType()));
        }
        promotion.setDiscountValue(dto.getDiscountValue());
        promotion.setStartDate(dto.getStartDate());
        promotion.setEndDate(dto.getEndDate());
        Boolean active = dto.getActive();
        promotion.setActive(active != null ? active : true);

        if (dto.getTargets() != null) {
            if (promotion.getTargets() == null) {
                promotion.setTargets(new ArrayList<>());
            }
            for (PromotionTargetDTO tdto : dto.getTargets()) {
                PromotionTarget target = new PromotionTarget();
                target.setTargetType(tdto.getTargetType());
                target.setTargetId(tdto.getTargetId());
                target.setPromotion(promotion);
                promotion.getTargets().add(target);
            }
        }

        if (dto.getBundleItems() != null) {
            if (promotion.getBundleItems() == null) {
                promotion.setBundleItems(new ArrayList<>());
            }
            for (BundleItemDTO bdto : dto.getBundleItems()) {
                BundleItem item = new BundleItem();
                Long mainProductId = bdto.getMainProductId() != null ? bdto.getMainProductId() : bdto.getProductId();
                Integer mainQty = bdto.getMainQuantity() != null ? bdto.getMainQuantity() : bdto.getQuantity();
                Long giftProductId = bdto.getGiftProductId() != null ? bdto.getGiftProductId() : bdto.getProductId();
                Integer giftQty = bdto.getGiftQuantity() != null ? bdto.getGiftQuantity() : bdto.getQuantity();
                String giftDiscountType = bdto.getGiftDiscountType() != null ? bdto.getGiftDiscountType() : "FREE";
                Double giftDiscountValueObj = bdto.getGiftDiscountValue();
                Double giftDiscountValue = giftDiscountValueObj != null ? giftDiscountValueObj : 0.0;
                String status = bdto.getStatus() != null ? bdto.getStatus() : "ACTIVE";

                item.setProductId(mainProductId);
                item.setQuantity(mainQty);
                item.setMainProductId(mainProductId);
                item.setMainQuantity(mainQty);
                item.setGiftProductId(giftProductId);
                item.setGiftQuantity(giftQty);
                item.setGiftDiscountType(giftDiscountType);
                item.setGiftDiscountValue(giftDiscountValue);
                item.setStatus(status);
                item.setPromotion(promotion);
                promotion.getBundleItems().add(item);
            }
        }
    }

    private void validatePromotionDTO(PromotionDTO dto, Long currentId) {
        if (dto == null) {
            throw new IllegalArgumentException("Promotion payload must not be null");
        }

        String code = dto.getCode() != null ? dto.getCode().trim() : "";
        if (code.isEmpty()) {
            throw new IllegalArgumentException("Mã khuyến mãi không được để trống");
        }

        String name = dto.getName() != null ? dto.getName().trim() : "";
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Tên khuyến mãi không được để trống");
        }

        if (dto.getDiscountType() == null) {
            throw new IllegalArgumentException("Loại giảm giá phải được chọn");
        }

        if (dto.getDiscountValue() == null) {
            throw new IllegalArgumentException("Giá trị giảm phải được nhập");
        }

        double value = dto.getDiscountValue();
        if (value < 0) {
            throw new IllegalArgumentException("Giá trị giảm không được âm");
        }
        if (dto.getDiscountType() == Promotion.DiscountType.PERCENT && (value <= 0 || value > 100)) {
            throw new IllegalArgumentException("Phần trăm giảm phải từ 1 đến 100");
        }

        if (dto.getStartDate() != null && dto.getEndDate() != null
                && dto.getEndDate().isBefore(dto.getStartDate())) {
            throw new IllegalArgumentException("Ngày kết thúc phải sau ngày bắt đầu");
        }
        if (dto.getStartDate() == null) {
            throw new IllegalArgumentException("Ngày bắt đầu không được để trống");
        }
        if (dto.getEndDate() == null) {
            throw new IllegalArgumentException("Ngày kết thúc không được để trống");
        }

        validateTargets(dto);
        validateBundleItems(dto);

        promotionRepository.findByCode(code)
                .filter(existing -> currentId == null || !existing.getId().equals(currentId))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("Mã khuyến mãi đã tồn tại. Vui lòng chọn mã khác.");
                });
    }

    private void validateTargets(PromotionDTO dto) {
        if (dto.getTargets() == null) {
            return;
        }
        for (PromotionTargetDTO target : dto.getTargets()) {
            if (target == null) {
                throw new IllegalArgumentException("Promotion target must not be null");
            }
            if (target.getTargetType() == null) {
                throw new IllegalArgumentException("Promotion target type must be provided");
            }
            if (target.getTargetId() == null || target.getTargetId() <= 0) {
                throw new IllegalArgumentException("Promotion target id must be a positive number");
            }
        }
    }

    private void validateBundleItems(PromotionDTO dto) {
        if (dto.getBundleItems() == null) {
            return;
        }
        if (dto.getDiscountType() != null && dto.getDiscountType() != Promotion.DiscountType.BUNDLE
                && !dto.getBundleItems().isEmpty()) {
            throw new IllegalArgumentException("Only bundle promotions may include bundle items");
        }
        if (dto.getDiscountType() == Promotion.DiscountType.BUNDLE && dto.getBundleItems().isEmpty()) {
            throw new IllegalArgumentException("Bundle promotions must include at least one bundle item");
        }
        for (BundleItemDTO item : dto.getBundleItems()) {
            if (item == null) {
                throw new IllegalArgumentException("Bundle item must not be null");
            }
            Long mainProductId = item.getMainProductId() != null ? item.getMainProductId() : item.getProductId();
            Long giftProductId = item.getGiftProductId() != null ? item.getGiftProductId() : item.getProductId();
            Integer mainQty = item.getMainQuantity() != null ? item.getMainQuantity() : item.getQuantity();
            Integer giftQty = item.getGiftQuantity() != null ? item.getGiftQuantity() : item.getQuantity();

            if (mainProductId == null || mainProductId <= 0) {
                throw new IllegalArgumentException("Bundle mainProductId must be a positive number");
            }
            if (giftProductId == null || giftProductId <= 0) {
                throw new IllegalArgumentException("Bundle giftProductId must be a positive number");
            }
            if (mainQty == null || mainQty <= 0) {
                throw new IllegalArgumentException("Bundle mainQuantity must be a positive number");
            }
            if (giftQty == null || giftQty <= 0) {
                throw new IllegalArgumentException("Bundle giftQuantity must be a positive number");
            }
        }
    }

    private Promotion.DiscountType normalizeDiscountType(Promotion promotion) {
        Promotion.DiscountType raw = promotion.getDiscountType();
        String promotionType = promotion.getPromotionType();

        Promotion.DiscountType fromPromotionType = mapPromotionType(promotionType);
        if (fromPromotionType != null) {
            if (raw == null || raw == Promotion.DiscountType.BUNDLE || raw == Promotion.DiscountType.FREE_GIFT
                    || raw == Promotion.DiscountType.FIXED_AMOUNT) {
                return fromPromotionType;
            }
        }

        return mapDiscountType(raw);
    }

    private Promotion.DiscountType mapDiscountType(Promotion.DiscountType raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw) {
            case FIXED_AMOUNT -> Promotion.DiscountType.FIXED;
            case FREE_GIFT -> Promotion.DiscountType.BUNDLE;
            default -> raw;
        };
    }

    private Promotion.DiscountType mapPromotionType(String promotionType) {
        if (promotionType == null || promotionType.isBlank()) {
            return null;
        }
        return switch (promotionType) {
            case "PERCENT" -> Promotion.DiscountType.PERCENT;
            case "FIXED_AMOUNT", "FIXED" -> Promotion.DiscountType.FIXED;
            case "FREE_GIFT", "BUNDLE" -> Promotion.DiscountType.BUNDLE;
            default -> null;
        };
    }

    private Promotion.DiscountType parseDiscountType(String discountType) {
        if (discountType == null || discountType.isBlank()) {
            return null;
        }
        try {
            return Promotion.DiscountType.valueOf(discountType.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String buildPromoPrefixFromName(String name) {
        String base = normalizePromotionName(name);
        if (base.isBlank()) {
            return "KM";
        }
        String[] words = base.split("\\s+");
        StringBuilder initials = new StringBuilder();
        for (String word : words) {
            if (!word.isBlank()) {
                initials.append(Character.toUpperCase(word.charAt(0)));
            }
        }
        String shortCode = initials.length() >= 2
                ? initials.toString()
                : base.replace(" ", "").substring(0, Math.min(4, base.length())).toUpperCase();
        return "KM" + shortCode;
    }

    private String normalizePromotionName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty()) {
            return "";
        }
        String normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        normalized = normalized.replaceAll("[^a-zA-Z0-9\\s]", " ");
        normalized = normalized.replaceAll("\\s+", " ").trim();
        return normalized;
    }

    private PromotionTarget.TargetType parseTargetType(String targetType) {
        if (targetType == null || targetType.isBlank()) {
            return null;
        }
        try {
            return PromotionTarget.TargetType.valueOf(targetType.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String mapPromotionTypeFromDiscount(Promotion.DiscountType discountType) {
        if (discountType == null) {
            return null;
        }
        return switch (discountType) {
            case PERCENT -> "PERCENT";
            case FIXED, FIXED_AMOUNT -> "FIXED_AMOUNT";
            case FREE_GIFT -> "FREE_GIFT";
            case BUNDLE -> "BUNDLE";
        };
    }

    private void evictPromotionByCode(String code) {
        if (code == null || code.isBlank()) {
            return;
        }
        Cache cache = cacheManager.getCache("promotionByCode");
        if (cache != null) {
            cache.evict(code);
        }
    }

    private void evictActivePromotionByCode(String code) {
        if (code == null || code.isBlank()) {
            return;
        }
        Cache cache = cacheManager.getCache("activePromotionByCode");
        if (cache != null) {
            cache.evict(code);
        }
    }

    private void refreshActivePromotionCache(PromotionDTO dto) {
        if (dto == null || dto.getCode() == null || dto.getCode().isBlank()) {
            return;
        }
        Cache cache = cacheManager.getCache("activePromotionByCode");
        if (cache == null) {
            return;
        }
        String codeKey = dto.getCode();
        if (isActiveNow(dto, LocalDateTime.now())) {
            if (codeKey != null) {
                cache.put(codeKey, dto);
            }
        } else {
            if (codeKey != null) {
                cache.evict(codeKey);
            }
        }
    }

    private boolean isActiveNow(PromotionDTO dto, LocalDateTime now) {
        if (dto.getActive() != null && !dto.getActive()) {
            return false;
        }
        if (dto.getStartDate() != null && dto.getStartDate().isAfter(now)) {
            return false;
        }
        return dto.getEndDate() == null || !dto.getEndDate().isBefore(now);
    }

    private void emitNifiSignalAfterCommit(String eventType, PromotionDTO dto) {
        if (!isNifiSignalEnabled()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sendNifiSignal(eventType, dto);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sendNifiSignal(eventType, dto);
            }
        });
    }

    private boolean isNifiSignalEnabled() {
        return nifiSignalUrl != null && !nifiSignalUrl.isBlank();
    }

    @Override
    public List<CartItemPriceResponse> calculateCartItemPrices(CartItemPriceRequest request) {
        List<CartItemPriceResponse> responses = new ArrayList<>();
        
        // Load all active promotions
        List<PromotionDTO> activePromos = getActivePromotions();
        
        for (CartItemPriceRequest.CartItem item : request.getItems()) {
            CartItemPriceResponse response = calculateSingleItemPrice(item, activePromos);
            responses.add(response);
        }
        
        return responses;
    }
    
    private CartItemPriceResponse calculateSingleItemPrice(
            CartItemPriceRequest.CartItem item,
            List<PromotionDTO> promotions
    ) {
        java.math.BigDecimal basePrice = item.getBasePrice() == null
                ? java.math.BigDecimal.ZERO
                : item.getBasePrice();

        PromotionDTO bestPromo = null;
        java.math.BigDecimal bestPrice = null;

        for (PromotionDTO promo : promotions) {
            if (promo == null || !appliesToItem(promo, item)) {
                continue;
            }
            java.math.BigDecimal candidate = calculateDiscountedPrice(basePrice, promo);
            if (candidate == null) {
                continue;
            }
            if (bestPrice == null || candidate.compareTo(bestPrice) < 0) {
                bestPrice = candidate;
                bestPromo = promo;
            }
        }

        if (bestPrice == null) {
            bestPrice = basePrice;
        }

        java.math.BigDecimal discount = basePrice.subtract(bestPrice).max(java.math.BigDecimal.ZERO);

        return new CartItemPriceResponse(
                item.getProductId(),
                basePrice,
                bestPrice,
                discount,
                bestPromo == null ? null : bestPromo.getCode(),
                bestPromo == null ? null : bestPromo.getName(),
                bestPromo == null || bestPromo.getDiscountType() == null ? null : bestPromo.getDiscountType().name(),
                item.getQuantity()
        );
    }

    private boolean appliesToItem(PromotionDTO promo, CartItemPriceRequest.CartItem item) {
        Long productId = item.getProductId();
        Long categoryId = item.getCategoryId();

        List<PromotionTargetDTO> targets = promo.getTargets();
        if (targets != null) {
            for (PromotionTargetDTO target : targets) {
                if (target == null || target.getTargetId() == null || target.getTargetType() == null) {
                    continue;
                }
                if (target.getTargetType() == com.example.promotion.entity.PromotionTarget.TargetType.PRODUCT
                        && productId != null
                        && productId.equals(target.getTargetId())) {
                    return true;
                }
                if (target.getTargetType() == com.example.promotion.entity.PromotionTarget.TargetType.CATEGORY
                        && categoryId != null
                        && categoryId.equals(target.getTargetId())) {
                    return true;
                }
            }
        }

        List<BundleItemDTO> bundleItems = promo.getBundleItems();
        if (bundleItems != null) {
            for (BundleItemDTO bundle : bundleItems) {
                if (bundle == null || bundle.getProductId() == null) {
                    continue;
                }
                if (productId != null && productId.equals(bundle.getProductId())) {
                    return true;
                }
            }
        }

        return false;
    }

    private java.math.BigDecimal calculateDiscountedPrice(java.math.BigDecimal basePrice, PromotionDTO promo) {
        if (basePrice == null || promo == null || promo.getDiscountType() == null) {
            return null;
        }

        java.math.BigDecimal value = promo.getDiscountValue() == null
                ? null
                : java.math.BigDecimal.valueOf(promo.getDiscountValue());

        return switch (promo.getDiscountType()) {
            case PERCENT -> {
                if (value == null) {
                    yield null;
                }
                java.math.BigDecimal percent = value.divide(java.math.BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP);
                yield basePrice.multiply(java.math.BigDecimal.ONE.subtract(percent)).max(java.math.BigDecimal.ZERO);
            }
            case FIXED, FIXED_AMOUNT -> value == null ? null : basePrice.subtract(value).max(java.math.BigDecimal.ZERO);
            case BUNDLE -> value == null ? null : value.max(java.math.BigDecimal.ZERO);
            case FREE_GIFT -> basePrice;
        };
    }

    private void sendNifiSignal(String eventType, PromotionDTO dto) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventType", eventType);
        payload.put("promotion", dto);
        payload.put("timestamp", LocalDateTime.now());

        try {
            String url = nifiSignalUrl;
            if (url != null) {
                restTemplate.postForEntity(url, payload, Void.class);
            }
        } catch (org.springframework.web.client.RestClientException ex) {
            log.warn("Failed to send NiFi signal to {}", nifiSignalUrl, ex);
        }
    }
}
