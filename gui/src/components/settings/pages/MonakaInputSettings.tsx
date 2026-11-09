import { Navigate, useLocation } from 'react-router-dom';
import { inputAssignmentsPath } from '@/monaka-input/home-catalog';

/** Preserve existing bookmarks while keeping all input editing on the body assignment page. */
export function MonakaInputSettings() {
  const { search } = useLocation();
  return (
    <Navigate
      to={{ pathname: inputAssignmentsPath, search }}
      state={{ alonePage: true }}
      replace
    />
  );
}
