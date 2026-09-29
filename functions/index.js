const { onSchedule } = require('firebase-functions/v2/scheduler');
const { logger } = require('firebase-functions');

// DEC-003b / DEC-003e-2: both tombstones and orphan photos retain for 30 days.
// FB-502 will use this single value for both eligibility checks.
const RETENTION_DAYS = 30;

exports.cleanupExpiredData = onSchedule(
  {
    schedule: '0 3 * * *',
    timeZone: 'Etc/UTC',
    region: 'us-central1',
    maxInstances: 1,
    concurrency: 1,
  },
  async () => {
    // FB-502 and FB-503 add the deletion passes here. Until then the scheduled
    // target is deliberately read/write-free; FB-507 controls deployment.
    logger.info('Cleanup scaffold invoked; deletion passes are pending FB-502 and FB-503.', {
      retentionDays: RETENTION_DAYS,
    });
  },
);
