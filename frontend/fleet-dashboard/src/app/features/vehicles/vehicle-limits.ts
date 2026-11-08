// Text lengths and serviceIntervalKm match the Fleet API contract.
// nextServiceAtKm is also bounded by JavaScript's exact integer representation.
export const VEHICLE_LIMITS = {
  externalCodeMaxLength: 64,
  plateMaxLength: 16,
  serviceIntervalMinKm: 1,
  serviceIntervalMaxKm: 2147483647,
  nextServiceMinKm: 0,
  nextServiceMaxKm: Number.MAX_SAFE_INTEGER,
} as const;
