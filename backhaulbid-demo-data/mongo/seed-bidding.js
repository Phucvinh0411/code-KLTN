// MongoDB seed script for backhaulbid_bidding database
db = db.getSiblingDB('backhaulbid_bidding');
db.createCollection('auctions');
db.createCollection('bids');
db.createCollection('auction_registrations');
print('Initialized backhaulbid_bidding collections');
