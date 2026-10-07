ALTER TABLE accounts ADD COLUMN IF NOT EXISTS custom_fields jsonb;
ALTER TABLE campaigns ADD COLUMN IF NOT EXISTS custom_fields jsonb;
ALTER TABLE contacts ADD COLUMN IF NOT EXISTS custom_fields jsonb;
ALTER TABLE leads ADD COLUMN IF NOT EXISTS custom_fields jsonb;
ALTER TABLE opportunities ADD COLUMN IF NOT EXISTS custom_fields jsonb;
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS custom_fields jsonb;

CREATE OR REPLACE FUNCTION ffcrm_yaml_string_array(p_yaml text) RETURNS jsonb
LANGUAGE plpgsql IMMUTABLE AS
$$
DECLARE
    v_line text;
    v_scalar text;
    v_out jsonb := '[]'::jsonb;
    v_rest text;
    v_i int;
    v_char text;
    v_value text;
    v_started boolean := false;
    v_lines text[];
BEGIN
    IF p_yaml IS NULL THEN
        RETURN NULL;
    END IF;
    v_rest := btrim(p_yaml, E' \n\t');
    IF v_rest = '--- []' OR v_rest = '[]' THEN
        RETURN '[]'::jsonb;
    END IF;
    v_lines := regexp_split_to_array(p_yaml, E'\n');
    FOREACH v_line IN ARRAY v_lines LOOP
        v_line := btrim(v_line, E' \t');
        CONTINUE WHEN v_line = '';
        CONTINUE WHEN v_line = '---' OR v_line LIKE '--- %';
        IF v_line !~ '^-\s*' THEN
            RETURN NULL;
        END IF;
        v_scalar := btrim(substring(v_line from '^-\s*(.*)$'), E' \t');
        IF v_scalar = '' OR v_scalar ~ '^[|>]' THEN
            RETURN NULL;
        END IF;
        IF v_scalar LIKE '- %' OR v_scalar = '-' OR v_scalar ~ '^-\s' THEN
            RETURN NULL;
        END IF;
        IF left(v_scalar, 1) = '''' THEN
            IF right(v_scalar, 1) <> '''' OR length(v_scalar) < 2 THEN
                RETURN NULL;
            END IF;
            v_value := replace(substring(v_scalar from 2 for length(v_scalar) - 2), '''''', '''');
            v_out := v_out || to_jsonb(v_value);
        ELSIF left(v_scalar, 1) = '"' THEN
            IF right(v_scalar, 1) <> '"' OR length(v_scalar) < 2 THEN
                RETURN NULL;
            END IF;
            v_scalar := substring(v_scalar from 2 for length(v_scalar) - 2);
            v_value := '';
            v_i := 1;
            WHILE v_i <= length(v_scalar) LOOP
                v_char := substr(v_scalar, v_i, 1);
                IF v_char = '\' THEN
                    v_i := v_i + 1;
                    v_char := substr(v_scalar, v_i, 1);
                    IF v_char = 'n' THEN v_value := v_value || E'\n';
                    ELSIF v_char = 't' THEN v_value := v_value || E'\t';
                    ELSIF v_char = 'r' THEN v_value := v_value || E'\r';
                    ELSIF v_char = 'b' THEN v_value := v_value || E'\b';
                    ELSIF v_char = 'f' THEN v_value := v_value || E'\f';
                    ELSIF v_char = '"' THEN v_value := v_value || '"';
                    ELSIF v_char = '\' THEN v_value := v_value || '\';
                    ELSIF v_char = '/' THEN v_value := v_value || '/';
                    ELSIF v_char = 'u' THEN
                        v_value := v_value || chr(('x' || substr(v_scalar, v_i + 1, 4))::bit(32)::int);
                        v_i := v_i + 4;
                    ELSE
                        RETURN NULL;
                    END IF;
                ELSE
                    v_value := v_value || v_char;
                END IF;
                v_i := v_i + 1;
            END LOOP;
            v_out := v_out || to_jsonb(v_value);
        ELSE
            v_out := v_out || to_jsonb(v_scalar);
        END IF;
        v_started := true;
    END LOOP;
    IF NOT v_started THEN
        RETURN NULL;
    END IF;
    RETURN v_out;
END;
$$;

CREATE OR REPLACE FUNCTION ffcrm_sync_custom_fields() RETURNS trigger
LANGUAGE plpgsql AS
$$
DECLARE
    v_key text;
    v_val jsonb;
    v_obj jsonb := '{}'::jsonb;
    v_cb_names text[];
    v_cb_names_loaded boolean := false;
    v_decoded jsonb;
    v_remove text[] := '{}';
    v_document jsonb;
BEGIN
    -- FFCRM has no triggers that issue nested writes; this guard prevents recursion.
    IF pg_trigger_depth() > 1 THEN
        RETURN NEW;
    END IF;

    FOR v_key, v_val IN
        SELECT key, value
          FROM jsonb_each(to_jsonb(NEW))
         WHERE key LIKE 'cf\_%' ESCAPE '\'
    LOOP
        v_remove := v_remove || v_key;
        IF v_val = 'null'::jsonb THEN
            CONTINUE;
        END IF;
        IF jsonb_typeof(v_val) = 'string' AND (v_val #>> '{}') ~ '^\s*(---|\[)' THEN
            IF NOT v_cb_names_loaded THEN
                SELECT array_agg(f.name) INTO v_cb_names
                  FROM fields f
                  JOIN field_groups g ON g.id = f.field_group_id
                 WHERE g.klass_name = TG_ARGV[0]
                   AND f."as" = 'check_boxes';
                v_cb_names := coalesce(v_cb_names, '{}');
                v_cb_names_loaded := true;
            END IF;
            IF v_key = ANY(v_cb_names) THEN
                v_decoded := ffcrm_yaml_string_array(v_val #>> '{}');
                IF v_decoded IS NULL THEN
                    IF TG_OP = 'UPDATE'
                       AND to_jsonb(OLD) -> v_key IS NOT DISTINCT FROM to_jsonb(NEW) -> v_key
                       AND jsonb_typeof(NEW.custom_fields -> v_key) = 'array' THEN
                        v_obj := v_obj || jsonb_build_object(v_key, NEW.custom_fields -> v_key);
                    ELSE
                        v_obj := v_obj || jsonb_build_object(
                            v_key, jsonb_build_object('$yaml', v_val #>> '{}'));
                    END IF;
                ELSE
                    v_obj := v_obj || jsonb_build_object(v_key, v_decoded);
                END IF;
            ELSE
                v_obj := v_obj || jsonb_build_object(v_key, v_val);
            END IF;
        ELSE
            v_obj := v_obj || jsonb_build_object(v_key, v_val);
        END IF;
    END LOOP;

    v_document := (coalesce(NEW.custom_fields, '{}'::jsonb) - v_remove) || v_obj;
    IF v_document IS DISTINCT FROM NEW.custom_fields THEN
        NEW.custom_fields := v_document;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER ffcrm_sync_custom_fields BEFORE INSERT OR UPDATE ON accounts
FOR EACH ROW EXECUTE FUNCTION ffcrm_sync_custom_fields('Account');
CREATE TRIGGER ffcrm_sync_custom_fields BEFORE INSERT OR UPDATE ON campaigns
FOR EACH ROW EXECUTE FUNCTION ffcrm_sync_custom_fields('Campaign');
CREATE TRIGGER ffcrm_sync_custom_fields BEFORE INSERT OR UPDATE ON contacts
FOR EACH ROW EXECUTE FUNCTION ffcrm_sync_custom_fields('Contact');
CREATE TRIGGER ffcrm_sync_custom_fields BEFORE INSERT OR UPDATE ON leads
FOR EACH ROW EXECUTE FUNCTION ffcrm_sync_custom_fields('Lead');
CREATE TRIGGER ffcrm_sync_custom_fields BEFORE INSERT OR UPDATE ON opportunities
FOR EACH ROW EXECUTE FUNCTION ffcrm_sync_custom_fields('Opportunity');
CREATE TRIGGER ffcrm_sync_custom_fields BEFORE INSERT OR UPDATE ON tasks
FOR EACH ROW EXECUTE FUNCTION ffcrm_sync_custom_fields('Task');
