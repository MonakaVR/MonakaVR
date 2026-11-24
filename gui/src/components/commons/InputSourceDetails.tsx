/** Public identifiers are available on demand, outside the primary label. */
export function InputSourceDetails({
  text,
  physicalId,
}: {
  text: string | undefined;
  physicalId?: string;
}) {
  if (!text) return null;
  return (
    <details className="group min-w-0 text-xs font-normal">
      {physicalId ? (
        <summary className="flex cursor-pointer gap-1 [&::-webkit-details-marker]:hidden">
          <span className="shrink-0">
            <span
              aria-hidden="true"
              className="inline-block group-open:rotate-90"
            >
              ▶
            </span>
            ID :
          </span>{' '}
          <span className="min-w-0 truncate" title={physicalId}>
            {physicalId}
          </span>
        </summary>
      ) : (
        <summary className="cursor-pointer">ID</summary>
      )}
      <pre className="whitespace-pre-wrap break-all max-w-full">{text}</pre>
    </details>
  );
}
