// Literal process.env.NEXT_PUBLIC_* member access is required: Next inlines these at build time.
// Getters keep the read lazy so tests can stub the environment. The airlock may be empty: the user
// picks one in the composer.
export const env = {
  get aisolateUrl() {
    return process.env.NEXT_PUBLIC_AISOLATE_URL ?? "";
  },
  get airlockId() {
    return process.env.NEXT_PUBLIC_AISOLATE_AIRLOCK_ID ?? "";
  },
};
