/** Public identifiers are available on demand, outside the primary label. */
export function InputSourceDetails({ text }: { text: string | undefined }) {
  if (!text) return null;
  return (
    <details className="min-w-0 text-xs font-normal">
      <summary className="cursor-pointer">ID</summary>
      <pre className="whitespace-pre-wrap break-all max-w-full">{text}</pre>
    </details>
  );
}
