import {
  Injectable,
  Logger,
  OnModuleDestroy,
  OnModuleInit,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import * as amqp from 'amqplib';

export const WALLET_EVENTS_EXCHANGE = 'wallet.events';
export const UNFREEZE_LOSERS_ROUTING_KEY = 'auction.unfreeze_losers';
export const UNFREEZE_LOSERS_QUEUE = 'auction.unfreeze_losers.queue';
export const REFUND_CREATION_FEE_ROUTING_KEY = 'auction.refund_creation_fee';
export const REFUND_CREATION_FEE_QUEUE = 'auction.refund_creation_fee.queue';

export interface UnfreezeLoserItem {
  userId: string;
  holdId?: string | null;
  amount: string;
  /** Định dạng chuẩn: `${auctionId}_${userId}_unfreeze` */
  idempotencyKey: string;
}

export interface UnfreezeLosersEvent {
  auctionId: string;
  losers: UnfreezeLoserItem[];
  occurredAt: string;
}

export interface RefundCreationFeeEvent {
  auctionId: string;
  shipperId: string;
  amount: string;
  purpose: string;
  idempotencyKey: string;
  occurredAt: string;
}

@Injectable()
export class WalletRmqPublisher implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(WalletRmqPublisher.name);
  private readonly rabbitUrl: string;
  private connection: amqp.ChannelModel | null = null;
  private channel: amqp.ConfirmChannel | null = null;

  constructor(config: ConfigService) {
    this.rabbitUrl =
      config.get<string>('RABBITMQ_URL') ?? 'amqp://guest:guest@localhost:5672';
  }

  async onModuleInit(): Promise<void> {
    try {
      await this.ensureChannel();
    } catch (error) {
      this.logger.warn(
        `Initial RabbitMQ connection deferred, will retry on publish: ${this.errorMessage(error)}`,
      );
    }
  }

  async onModuleDestroy(): Promise<void> {
    try {
      await this.channel?.close();
      await this.connection?.close();
    } catch {
      // Ignore shutdown errors
    } finally {
      this.channel = null;
      this.connection = null;
    }
  }

  /**
   * Edge Case 4: Phát sự kiện 'auction.unfreeze_losers' qua RabbitMQ với cơ chế retry
   * để đảm bảo tính nhất quán phân tán khi hoàn cọc cho các nhà xe trượt thầu.
   */
  async publishUnfreezeLosers(event: UnfreezeLosersEvent): Promise<boolean> {
    return this.publishWithRetry(UNFREEZE_LOSERS_ROUTING_KEY, event);
  }

  /**
   * Edge Case 2: Phát sự kiện 'auction.refund_creation_fee' qua RabbitMQ sang wallet-service
   * để hoàn lại phí tạo phiên (Creation Fee) cho Shipper khi phiên không có ai đấu giá.
   */
  async publishRefundCreationFee(
    event: RefundCreationFeeEvent,
  ): Promise<boolean> {
    return this.publishWithRetry(REFUND_CREATION_FEE_ROUTING_KEY, event);
  }

  private async publishWithRetry(
    routingKey: string,
    payload: unknown,
    maxRetries = 3,
  ): Promise<boolean> {
    for (let attempt = 1; attempt <= maxRetries; attempt++) {
      try {
        const ch = await this.ensureChannel();
        const messageBuffer = Buffer.from(JSON.stringify(payload), 'utf-8');

        await new Promise<void>((resolve, reject) => {
          ch.publish(
            WALLET_EVENTS_EXCHANGE,
            routingKey,
            messageBuffer,
            {
              persistent: true,
              contentType: 'application/json',
              timestamp: Date.now(),
            },
            (err) => {
              if (err) reject(err);
              else resolve();
            },
          );
        });

        this.logger.log(
          `[RabbitMQ] Published '${routingKey}' successfully (attempt ${attempt}/${maxRetries})`,
        );
        return true;
      } catch (error) {
        this.logger.warn(
          `[RabbitMQ] Publish '${routingKey}' failed (attempt ${attempt}/${maxRetries}): ${this.errorMessage(error)}`,
        );
        this.resetConnection();
        if (attempt < maxRetries) {
          const delayMs = 300 * Math.pow(2, attempt - 1);
          await new Promise((resolve) => setTimeout(resolve, delayMs));
        }
      }
    }
    return false;
  }

  private async ensureChannel(): Promise<amqp.ConfirmChannel> {
    if (this.channel) return this.channel;

    const conn = await amqp.connect(this.rabbitUrl);
    conn.on('error', (err) => {
      this.logger.warn(`[RabbitMQ] Connection error: ${this.errorMessage(err)}`);
      this.resetConnection();
    });
    conn.on('close', () => {
      this.resetConnection();
    });

    const ch = await conn.createConfirmChannel();
    await ch.assertExchange(WALLET_EVENTS_EXCHANGE, 'topic', { durable: true });

    await ch.assertQueue(UNFREEZE_LOSERS_QUEUE, { durable: true });
    await ch.bindQueue(
      UNFREEZE_LOSERS_QUEUE,
      WALLET_EVENTS_EXCHANGE,
      UNFREEZE_LOSERS_ROUTING_KEY,
    );

    await ch.assertQueue(REFUND_CREATION_FEE_QUEUE, { durable: true });
    await ch.bindQueue(
      REFUND_CREATION_FEE_QUEUE,
      WALLET_EVENTS_EXCHANGE,
      REFUND_CREATION_FEE_ROUTING_KEY,
    );

    this.connection = conn;
    this.channel = ch;
    return ch;
  }

  private resetConnection(): void {
    this.channel = null;
    this.connection = null;
  }

  private errorMessage(error: unknown): string {
    return error instanceof Error ? error.message : 'Unknown RabbitMQ error';
  }
}
