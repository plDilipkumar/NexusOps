export type AiSqlSchemaColumnInput = { name: string; dataType: string };
export type AiSqlSchemaTableInput = { name: string; columns: readonly AiSqlSchemaColumnInput[] };
export type AiSqlSchemaInput = { name: string; tables: readonly AiSqlSchemaTableInput[] };
export type AiSqlSchemaContext = {
  name: string;
  tables: { name: string; columns: AiSqlSchemaColumnInput[] }[];
};
export type AiSqlGenerationResponse = {
  sql: string;
  statementType: "SELECT" | "WITH";
  warnings: string[];
};

export const AI_SQL_LIMITS = {
  promptCodePoints: 2_000,
  schemas: 20,
  tables: 40,
  columnsPerTable: 25,
  totalColumns: 400,
  schemaJsonCharacters: 16_000,
} as const;

function isBoundedText(value: unknown, maximumLength: number): value is string {
  return typeof value === "string"
    && value.length > 0
    && value.length <= maximumLength
    && !/[\u0000-\u001f\u007f-\u009f]/u.test(value);
}

export function buildAiSchemaContext(
  input: readonly AiSqlSchemaInput[] | null | undefined,
): { schemas: AiSqlSchemaContext[]; truncated: boolean } {
  const source = Array.isArray(input) ? input : [];
  const schemas: AiSqlSchemaContext[] = [];
  let tableCount = 0;
  let totalColumnCount = 0;
  let truncated = source.length > AI_SQL_LIMITS.schemas;

  outer: for (const schema of source) {
    if (schemas.length >= AI_SQL_LIMITS.schemas) {
      truncated = true;
      break;
    }
    if (!schema || !isBoundedText(schema.name, 128)) {
      truncated = true;
      continue;
    }

    const safeSchema: AiSqlSchemaContext = { name: schema.name, tables: [] };
    if (JSON.stringify([...schemas, safeSchema]).length > AI_SQL_LIMITS.schemaJsonCharacters) {
      truncated = true;
      break;
    }
    schemas.push(safeSchema);

    const tables = Array.isArray(schema.tables) ? schema.tables : [];
    for (const table of tables) {
      if (tableCount >= AI_SQL_LIMITS.tables) {
        truncated = true;
        break outer;
      }
      if (!table || !isBoundedText(table.name, 128)) {
        truncated = true;
        continue;
      }

      const columns: AiSqlSchemaColumnInput[] = [];
      const sourceColumns = Array.isArray(table.columns) ? table.columns : [];
      for (const column of sourceColumns) {
        if (columns.length >= AI_SQL_LIMITS.columnsPerTable
            || totalColumnCount + columns.length >= AI_SQL_LIMITS.totalColumns) {
          truncated = true;
          break;
        }
        if (!column
            || !isBoundedText(column.name, 128)
            || !isBoundedText(column.dataType, 80)) {
          truncated = true;
          continue;
        }
        columns.push({ name: column.name, dataType: column.dataType });
      }

      const safeTable = { name: table.name, columns };
      const currentSchema = schemas[schemas.length - 1];
      const candidate = [
        ...schemas.slice(0, -1),
        { name: currentSchema.name, tables: [...currentSchema.tables, safeTable] },
      ];
      if (JSON.stringify(candidate).length > AI_SQL_LIMITS.schemaJsonCharacters) {
        truncated = true;
        break outer;
      }

      currentSchema.tables.push(safeTable);
      tableCount += 1;
      totalColumnCount += columns.length;
      if (totalColumnCount >= AI_SQL_LIMITS.totalColumns && sourceColumns.length > columns.length) {
        truncated = true;
        break outer;
      }
      if (sourceColumns.length > columns.length) truncated = true;
    }
  }

  return { schemas, truncated };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function parseAiSqlResponse(payload: unknown): AiSqlGenerationResponse {
  if (!isRecord(payload)
      || typeof payload.sql !== "string"
      || payload.sql.trim().length === 0
      || payload.sql.length > 10_000
      || (payload.statementType !== "SELECT" && payload.statementType !== "WITH")
      || !Array.isArray(payload.warnings)
      || payload.warnings.length > 8
      || payload.warnings.some((warning) => typeof warning !== "string" || warning.length > 500)) {
    throw new Error("The API returned an invalid AI SQL draft.");
  }

  return {
    sql: payload.sql.trim(),
    statementType: payload.statementType,
    warnings: payload.warnings as string[],
  };
}

export function replaceSqlDraft(generatedSql: string): string {
  const replacement = generatedSql.trim();
  if (!replacement || replacement.length > 10_000) {
    throw new Error("The generated SQL draft is empty or too large.");
  }
  return replacement;
}

export function createAiSqlRequestGuard() {
  let generation = 0;
  return {
    begin() {
      generation += 1;
      return generation;
    },
    invalidate() {
      generation += 1;
    },
    isCurrent(requestId: number) {
      return requestId === generation;
    },
  };
}

type GenerateAiSqlOptions = {
  apiBaseUrl: string;
  prompt: string;
  schemas: readonly AiSqlSchemaContext[];
  headers: Record<string, string>;
  signal?: AbortSignal;
};

function failureMessage(status: number): string {
  if (status === 400) return "The AI request was rejected. Shorten the prompt or refresh the visible schema.";
  if (status === 401) return "Your sign-in is no longer valid. Sign in again.";
  if (status === 403) return "Your account cannot generate SQL in the selected workspace.";
  if (status === 404) return "AI SQL generation is not enabled on the API.";
  if (status === 422) return "The generated draft failed the read-only safety checks. Try a more specific request.";
  if (status === 429) return "The AI generation limit has been reached. Wait before trying again.";
  if (status === 503) return "AI SQL generation is not configured or is temporarily unavailable.";
  return "The AI SQL request failed (HTTP " + status + ").";
}

function isAbortError(error: unknown): boolean {
  return isRecord(error) && error.name === "AbortError";
}

export async function generateAiSql(
  options: GenerateAiSqlOptions,
  fetcher: typeof fetch = fetch,
): Promise<AiSqlGenerationResponse> {
  const prompt = options.prompt.trim();
  const promptLength = Array.from(prompt).length;
  if (promptLength < 5
      || promptLength > AI_SQL_LIMITS.promptCodePoints
      || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/u.test(prompt)) {
    throw new Error("Describe the query in 5 to 2,000 characters without control characters.");
  }

  const authorization = options.headers.Authorization ?? options.headers.authorization;
  const workspaceId = options.headers["X-NeuroSQL-Workspace-Id"];
  if (!authorization || !/^Bearer \S+$/.test(authorization) || !workspaceId) {
    throw new Error("Sign in and select a workspace before generating SQL.");
  }
  if (options.schemas.length > AI_SQL_LIMITS.schemas
      || JSON.stringify(options.schemas).length > AI_SQL_LIMITS.schemaJsonCharacters) {
    throw new Error("The visible schema context is larger than the supported AI request limit. Refresh the schema and try again.");
  }

  const baseUrl = options.apiBaseUrl.endsWith("/")
    ? options.apiBaseUrl.slice(0, -1)
    : options.apiBaseUrl;

  let response: Response;
  try {
    response = await fetcher(baseUrl + "/api/sql/ai/generate", {
      method: "POST",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
        ...options.headers,
      },
      body: JSON.stringify({ prompt, schemas: options.schemas }),
      cache: "no-store",
      credentials: "omit",
      signal: options.signal,
    });
  } catch (error) {
    if (options.signal?.aborted || isAbortError(error)) throw error;
    throw new Error("The AI request could not reach the NeuroSQL API. Check the connection and try again.");
  }

  let payload: unknown = null;
  try {
    payload = await response.json();
  } catch {
    payload = null;
  }
  if (!response.ok) throw new Error(failureMessage(response.status));
  return parseAiSqlResponse(payload);
}
