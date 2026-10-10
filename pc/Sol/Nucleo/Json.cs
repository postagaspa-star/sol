using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;

namespace Sol.Nucleo;

/// <summary>Le opzioni JSON del contratto (nomi snake_case) e due aiuti per leggere con tolleranza.</summary>
public static class Json
{
    public static readonly JsonSerializerOptions Contratto = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
        PropertyNameCaseInsensitive = true,
        NumberHandling = JsonNumberHandling.AllowReadingFromString,
        DefaultIgnoreCondition = JsonIgnoreCondition.Never,
    };

    public static readonly JsonSerializerOptions File = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
        PropertyNameCaseInsensitive = true,
        WriteIndented = true,
    };

    /// <summary>Il testo come oggetto del contratto, o null se non è JSON valido di quella forma.</summary>
    public static T? Leggi<T>(string? testo) where T : class
    {
        if (string.IsNullOrWhiteSpace(testo)) return null;
        try
        {
            return JsonSerializer.Deserialize<T>(testo, Contratto);
        }
        catch (JsonException)
        {
            return null;
        }
        catch (NotSupportedException)
        {
            return null;
        }
    }

    public static string Scrivi<T>(T valore) => JsonSerializer.Serialize(valore, Contratto);

    public static JsonNode? Nodo(string? testo)
    {
        if (string.IsNullOrWhiteSpace(testo)) return null;
        try
        {
            return JsonNode.Parse(testo);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    public static string? Testo(JsonNode? n)
    {
        if (n is not JsonValue v) return null;
        if (v.TryGetValue<string>(out var s)) return s;
        if (v.TryGetValue<long>(out var l)) return l.ToString(System.Globalization.CultureInfo.InvariantCulture);
        return null;
    }

    public static long? Intero(JsonNode? n)
    {
        if (n is not JsonValue v) return null;
        if (v.TryGetValue<long>(out var l)) return l;
        if (v.TryGetValue<int>(out var i)) return i;
        if (v.TryGetValue<double>(out var d) && d == Math.Floor(d)) return (long)d;
        if (v.TryGetValue<string>(out var s) && long.TryParse(s, System.Globalization.NumberStyles.Integer, System.Globalization.CultureInfo.InvariantCulture, out var p)) return p;
        return null;
    }

    public static bool? Booleano(JsonNode? n) =>
        n is JsonValue v && v.TryGetValue<bool>(out var b) ? b : null;
}

/// <summary>Accetta sia <c>"8131"</c> sia <c>8131</c>: il cursore delle novità è opaco, conta solo restituirlo uguale.</summary>
public sealed class TestoONumero : JsonConverter<string?>
{
    public override string? Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options) => reader.TokenType switch
    {
        JsonTokenType.String => reader.GetString(),
        JsonTokenType.Number => reader.TryGetInt64(out var l) ? l.ToString(System.Globalization.CultureInfo.InvariantCulture) : reader.GetDouble().ToString(System.Globalization.CultureInfo.InvariantCulture),
        JsonTokenType.Null => null,
        _ => throw new JsonException("cursore non valido"),
    };

    public override void Write(Utf8JsonWriter writer, string? value, JsonSerializerOptions options)
    {
        if (value == null) writer.WriteNullValue();
        else writer.WriteStringValue(value);
    }
}
