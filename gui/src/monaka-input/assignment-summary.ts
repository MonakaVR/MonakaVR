import { createContext } from 'react';

// Display-only role summaries; never manufactured SolarXR trackers.
export const InputAssignmentSummaries = createContext<Record<number, string>>({});
