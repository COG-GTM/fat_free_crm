-- AB-267 dual-read spike: SQL-side YAML decoder + cf_* -> custom_fields sync trigger.
-- Anything spike_yaml_string_array cannot decode returns NULL, which the trigger
-- stores as a {"$yaml": <raw>} marker for the Java CheckBoxesYamlCodec to decode.

create or replace function spike_yaml_string_array(p_yaml text) returns jsonb
language plpgsql immutable as
$$
declare
    v_line text;
    v_scalar text;
    v_out jsonb := '[]'::jsonb;
    v_rest text;
    v_i int;
    v_char text;
    v_value text;
    v_started boolean := false;
    v_lines text[];
begin
    if p_yaml is null then
        return null;
    end if;
    v_rest := btrim(p_yaml, E' \n\t');
    if v_rest = '--- []' or v_rest = '[]' then
        return '[]'::jsonb;
    end if;
    v_lines := regexp_split_to_array(p_yaml, E'\n');
    foreach v_line in array v_lines loop
        v_line := btrim(v_line, E' \t');
        continue when v_line = '';
        continue when v_line = '---' or v_line like '--- %';
        if v_line !~ '^-\s*' then
            return null; -- not a simple sequence line (nested map, doc end, ...)
        end if;
        v_scalar := btrim(substring(v_line from '^-\s*(.*)$'), E' \t');
        if v_scalar = '' or v_scalar ~ '^[|>]' then
            return null; -- block scalar (|-, |, >-, ...) or empty item
        end if;
        if v_scalar like '- %' or v_scalar = '-' or v_scalar ~ '^-\s' then
            return null; -- nested sequence
        end if;
        if left(v_scalar, 1) = '''' then
            -- single-quoted: must end with a quote, '' escapes '
            if right(v_scalar, 1) <> '''' or length(v_scalar) < 2 then
                return null;
            end if;
            v_value := replace(substring(v_scalar from 2 for length(v_scalar) - 2), '''''', '''');
            v_out := v_out || to_jsonb(v_value);
        elsif left(v_scalar, 1) = '"' then
            if right(v_scalar, 1) <> '"' or length(v_scalar) < 2 then
                return null;
            end if;
            v_scalar := substring(v_scalar from 2 for length(v_scalar) - 2);
            v_value := '';
            v_i := 1;
            while v_i <= length(v_scalar) loop
                v_char := substr(v_scalar, v_i, 1);
                if v_char = '\' then
                    v_i := v_i + 1;
                    v_char := substr(v_scalar, v_i, 1);
                    if v_char = 'n' then v_value := v_value || E'\n';
                    elsif v_char = 't' then v_value := v_value || E'\t';
                    elsif v_char = 'r' then v_value := v_value || E'\r';
                    elsif v_char = 'b' then v_value := v_value || E'\b';
                    elsif v_char = 'f' then v_value := v_value || E'\f';
                    elsif v_char = '"' then v_value := v_value || '"';
                    elsif v_char = '\' then v_value := v_value || '\';
                    elsif v_char = '/' then v_value := v_value || '/';
                    elsif v_char = 'u' then
                        v_value := v_value || chr(('x' || substr(v_scalar, v_i + 1, 4))::bit(32)::int);
                        v_i := v_i + 4;
                    else
                        return null;
                    end if;
                else
                    v_value := v_value || v_char;
                end if;
                v_i := v_i + 1;
            end loop;
            v_out := v_out || to_jsonb(v_value);
        else
            -- plain scalar (unquoted); strings only in our fixtures
            v_out := v_out || to_jsonb(v_scalar);
        end if;
        v_started := true;
    end loop;
    if not v_started then
        return null;
    end if;
    return v_out;
end;
$$;

create or replace function spike_sync_custom_fields() returns trigger
language plpgsql as
$$
declare
    v_key text;
    v_val jsonb;
    v_obj jsonb := '{}'::jsonb;
    v_cb_names text[];
    v_decoded jsonb;
    v_remove text[] := '{}';
begin
    -- check_boxes columns for this Rails klass, per the fields/field_groups metadata
    select array_agg(f.name) into v_cb_names
      from fields f
      join field_groups g on g.id = f.field_group_id
     where g.klass_name = TG_ARGV[0]
       and f."as" = 'check_boxes';
    v_cb_names := coalesce(v_cb_names, '{}');

    for v_key, v_val in
        select key, value from jsonb_each(to_jsonb(NEW))
         where key like 'cf\_%' escape '\'
    loop
        v_remove := v_remove || v_key; -- every cf_ column name present in NEW
        if v_val = 'null'::jsonb then
            continue; -- a cf_ column set to NULL removes the key
        end if;
        if v_key = any(v_cb_names) then
            v_decoded := spike_yaml_string_array(v_val #>> '{}');
            if v_decoded is null then
                -- undecodable YAML: marker for the Java side to decode
                v_obj := v_obj || jsonb_build_object(v_key, jsonb_build_object('$yaml', v_val #>> '{}'));
            else
                v_obj := v_obj || jsonb_build_object(v_key, v_decoded);
            end if;
        else
            v_obj := v_obj || jsonb_build_object(v_key, v_val);
        end if;
    end loop;

    -- Java-only keys (no cf_ column) survive; cf_ columns win; NULL cf_ removes the key
    NEW.custom_fields := (coalesce(NEW.custom_fields, '{}'::jsonb) - v_remove) || v_obj;
    return NEW;
end;
$$;
