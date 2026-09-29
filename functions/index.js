const { onSchedule } = require('firebase-functions/v2/scheduler');
const { logger } = require('firebase-functions');
const { initializeApp, getApps } = require('firebase-admin/app');
const { getFirestore } = require('firebase-admin/firestore');
const { getStorage } = require('firebase-admin/storage');
const { runCleanup, RETENTION_DAYS } = require('./cleanup');

exports.cleanupExpiredData = onSchedule(
  {
    schedule: '0 3 * * *',
    timeZone: 'Etc/UTC',
    region: 'us-central1',
    maxInstances: 1,
    concurrency: 1,
    timeoutSeconds: 540,
  },
  async () => {
    if (!getApps().length) initializeApp();
    const result = await runCleanup({ db: getFirestore(), bucket: getStorage().bucket() });
    // FB-503 adds list cascade; FB-507 controls deployment.
    logger.info('Scheduled item/photo cleanup finished.', { ...result, retentionDays: RETENTION_DAYS });
  },
);
