export function optionalImportance(value, field) {
  if (value === undefined || value === null) return value
  if (!Number.isInteger(value) || value < 0 || value > 5) {
    throw new TypeError(`${field} must be an integer between 0 and 5, or null`)
  }
  return value
}
