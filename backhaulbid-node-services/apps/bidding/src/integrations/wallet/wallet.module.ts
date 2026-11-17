import { Module } from '@nestjs/common';
import { WalletClient } from './wallet.client';
import { WalletRmqPublisher } from './wallet-rmq.publisher';

@Module({
  providers: [WalletClient, WalletRmqPublisher],
  exports: [WalletClient, WalletRmqPublisher],
})
export class WalletModule {}
