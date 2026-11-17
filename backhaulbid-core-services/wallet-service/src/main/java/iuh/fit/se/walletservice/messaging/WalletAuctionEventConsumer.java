package iuh.fit.se.walletservice.messaging;

import iuh.fit.se.walletservice.config.RabbitMQConfig;
import iuh.fit.se.walletservice.dto.request.InternalWalletOperationRequest;
import iuh.fit.se.walletservice.service.InternalWalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * RabbitMQ Consumer xử lý bất đồng bộ các nghiệp vụ Ví từ bidding-service:
 * 1. Giải tỏa tiền cọc (Unfreeze) cho danh sách nhà xe trượt thầu (Edge Case 4).
 * 2. Hoàn lại phí tạo phiên (Creation Fee) cho Shipper khi phiên không có ai đấu giá (Edge Case 2).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WalletAuctionEventConsumer {

    private final InternalWalletService internalWalletService;

    public record UnfreezeLoserItem(
            String userId,
            String holdId,
            String amount,
            String idempotencyKey
    ) {}

    public record UnfreezeLosersEvent(
            String auctionId,
            List<UnfreezeLoserItem> losers,
            String occurredAt
    ) {}

    public record RefundCreationFeeEvent(
            String auctionId,
            String shipperId,
            String amount,
            String purpose,
            String idempotencyKey,
            String occurredAt
    ) {}

    /**
     * Edge Case 4: Lắng nghe sự kiện 'auction.unfreeze_losers' để giải tỏa tiền cọc
     * cho toàn bộ nhà xe trượt thầu với idempotencyKey = {auctionId}_{userId}_unfreeze.
     */
    @RabbitListener(queues = RabbitMQConfig.UNFREEZE_LOSERS_QUEUE)
    public void handleUnfreezeLosers(UnfreezeLosersEvent event) {
        if (event == null || event.auctionId() == null || event.losers() == null || event.losers().isEmpty()) {
            log.warn("[RabbitMQ] Received empty auction.unfreeze_losers event");
            return;
        }

        log.info("[RabbitMQ] Processing auction.unfreeze_losers for auction={} ({} losers)",
                event.auctionId(), event.losers().size());

        for (UnfreezeLoserItem item : event.losers()) {
            if (item == null || item.userId() == null) continue;

            UUID userId = UUID.fromString(item.userId());
            UUID holdId = (item.holdId() != null && !item.holdId().isBlank())
                    ? UUID.fromString(item.holdId())
                    : null;
            BigDecimal amount = (item.amount() != null && !item.amount().isBlank())
                    ? new BigDecimal(item.amount())
                    : BigDecimal.ZERO;

            // Khóa chống trùng lặp bắt buộc theo quy chuẩn: {auctionId}_{userId}_unfreeze
            String idempotencyKey = event.auctionId() + "_" + item.userId() + "_unfreeze";

            internalWalletService.unfreezeLoserDeposit(
                    event.auctionId(),
                    userId,
                    holdId,
                    amount,
                    idempotencyKey
            );
            log.info("[RabbitMQ] Unfroze deposit for loser userId={} auctionId={} idempotencyKey={}",
                    item.userId(), event.auctionId(), idempotencyKey);
        }
    }

    /**
     * Edge Case 2: Lắng nghe sự kiện 'auction.refund_creation_fee' để hoàn lại phí tạo phiên
     * cho Shipper khi phiên kết thúc mà không có lượt đặt giá nào (CANCELLED_NO_BIDS).
     */
    @RabbitListener(queues = RabbitMQConfig.REFUND_CREATION_FEE_QUEUE)
    public void handleRefundCreationFee(RefundCreationFeeEvent event) {
        if (event == null || event.shipperId() == null || event.amount() == null) {
            log.warn("[RabbitMQ] Received invalid auction.refund_creation_fee event");
            return;
        }

        UUID shipperId = UUID.fromString(event.shipperId());
        BigDecimal amount = new BigDecimal(event.amount());
        String idempotencyKey = (event.idempotencyKey() != null && !event.idempotencyKey().isBlank())
                ? event.idempotencyKey()
                : "auction_creation_fee_" + event.auctionId() + ":refund";

        InternalWalletOperationRequest request = new InternalWalletOperationRequest(
                amount,
                event.auctionId(),
                null,
                event.purpose() != null ? event.purpose() : "AUCTION_CREATION_FEE_REFUND",
                idempotencyKey
        );

        internalWalletService.refund(shipperId, request);
        log.info("[RabbitMQ] Refunded creation fee amount={} for shipperId={} auctionId={} idempotencyKey={}",
                amount, shipperId, event.auctionId(), idempotencyKey);
    }
}
