// This file is licensed under the Elastic License 2.0. Copyright 2021 StarRocks Limited.

#pragma once

#include <fmt/format.h>

#include <cstddef>
#include <cstdint>
#include <string>
#include <type_traits>

#include "runtime/integer_overflow_arithmetics.h"
#include "util/decimal_types.h"
#include "util/raw_container.h"
#include "util/string_parser.hpp"

namespace starrocks {
TYPE_GUARD(Decimal32Guard, is_decimal32, int32_t)
TYPE_GUARD(Decimal64Guard, is_decimal64, int64_t)
TYPE_GUARD(Decimal128Guard, is_decimal128, int128_t)
TYPE_GUARD(DecimalGuard, is_decimal, int32_t, int64_t, int128_t)

template <typename ST>
struct unsigned_type {
    using type = typename std::make_unsigned<ST>::type;
};
template <>
struct unsigned_type<int128_t> {
    using type = uint128_t;
};

template <typename T, bool check_overflow>
class DecimalV3Arithmetics {
public:
    using Type = std::enable_if_t<starrocks::is_underlying_type_of_decimal<T>, T>;
    static inline bool add(Type const& a, Type const& b, Type* c) {
        if (check_overflow) {
            return add_overflow(a, b, c);
        } else {
            *c = a + b;
            return false;
        }
    }

    static inline bool sub(Type const& a, Type const& b, Type* c) {
        if constexpr (check_overflow) {
            return sub_overflow(a, b, c);
        } else {
            *c = a - b;
            return false;
        }
    }

    static inline bool mul(Type const& a, Type const& b, Type* c) {
        if constexpr (check_overflow) {
            return mul_overflow(a, b, c);
        } else {
            *c = a * b;
            return false;
        }
    }

    // check divide-by-zero before calling div and mod
    static inline bool div(Type const& a, Type const& b, Type* c) {
        *c = a / b;
        return false;
    }

    // check divide-by-zero before calling div and mod
    static inline bool div_round(Type const& a, Type const& b, Type* c) {
        *c = a / b;
        Type r = a % b;
        // case 1: |b| is odd. if [|b|/2] < |r|, then add carry; otherwise add 0.
        // case 2: |b| is even. if [|b|/2] <= |r|, then add carry; otherwise add 0. here
        // [b/2] == r means round half to up.
        // carry depends on sign of a^b.
        Type carry = ((a ^ b) >> (sizeof(Type) * 8 - 1)) | 1;
        Type abs_b = abs(b);
        Type abs_r = abs(r);
        bool need_carry = ((abs_b >> 1) + (abs_b & 1)) <= abs_r;
        *c += carry & -Type(need_carry);
        return false;
    }

    static inline bool mod(Type const& a, Type const& b, Type* c) {
        *c = a % b;
        return false;
    }
};

enum DecimalRoundRule {
    ROUND_HALF_UP,
    ROUND_HALF_EVEN,
    ROUND_FLOOR,
    ROUND_CEILING,
    ROUND_TRUNCATE,
};

class DecimalV3Cast {
public:
    template <typename T>
    static inline bool from_string(DecimalType<T>* value, int precision, int scale, const char* s, size_t n) {
        StringParser::ParseResult result = StringParser::PARSE_SUCCESS;
        *value = StringParser::string_to_decimal<T>(s, n, precision, scale, &result);
        return result == StringParser::PARSE_FAILURE || result == StringParser::PARSE_OVERFLOW;
    }

    template <typename ST>
    static inline std::string to_string(DecimalType<ST> const& value, int precision, int scale) {
        using T = typename unsigned_type<ST>::type;
        static constexpr size_t str_decimal_max_len = decimal_precision_limit<ST> + 10;
        const T scale_factor = get_scale_factor<ST>(scale);
        std::string s;
        raw::make_room(&s, str_decimal_max_len);
        char* str_decimal = s.data();
        int len = 0;

        T abs_value = value;

        if (value < 0) {
            abs_value = -value;
            str_decimal[len++] = '-';
        }
        // divmod optimization
        T int_part = abs_value / scale_factor;
        T frac_part = abs_value % scale_factor;

        auto end = fmt::format_to(str_decimal + len, "{}", int_part);
        len = end - str_decimal;

        int low_scale = 0;
        int high_scale = scale;
        // bin search speedup computation for the number of dec-digits of the fraction part;
        while (low_scale < high_scale) {
            int mid_scale = (high_scale + low_scale) >> 1;
            T mid_scale_factor = get_scale_factor<ST>(mid_scale);
            if (mid_scale_factor <= frac_part) {
                low_scale = mid_scale + 1;
            } else {
                high_scale = mid_scale;
            }
        }
        // case 1: low_scale = 0, fraction part is zero, no fraction part to output;
        // case 2: low_scale = scale, no zeros between decimal point and first non-zero dec-digit
        //         of fraction part.
        // case 3: low_scale < scale, (scale-low_scale) zeros are interpolated into str_decimal.
        if (low_scale != 0) {
            str_decimal[len++] = '.';
            const size_t zeros_interpolated = scale - low_scale;
            for (size_t i = 0; i < zeros_interpolated; ++i) {
                str_decimal[len++] = '0';
            }
            end = fmt::format_to(str_decimal + len, "{}", frac_part);
            len = end - str_decimal;
        }
        s.resize(len);
        return s;
    }

    template <typename T>
    static constexpr T float_overflow_indicator = std::numeric_limits<T>::lowest();
    template <typename From, typename To>
    static inline bool from_float(FloatType<From> value, DecimalType<To> const& scale_factor,
                                  DecimalType<To>* dec_value) {
        *dec_value = static_cast<To>(scale_factor * static_cast<double>(value));
        if constexpr (is_decimal32<To> || is_decimal64<To>) {
            return *dec_value == float_overflow_indicator<To>;
        } else if constexpr (is_decimal128<To>) {
            // abs(value)<1.0 -> 0: Acceptable
            // abs(value)>=1.0 -> 0 or different sign: Overflow!!
            return abs(value) >= From(1) && (*dec_value == To(0) || ((value < From(0)) ^ (*dec_value < To(0))));
        } else {
            static_assert(is_decimal<To>, "invalid decimal type");
        }
    }

    template <typename From, typename To, bool check_overflow>
    static inline bool scale_up(IntegerType<From> from_value, DecimalType<To> const& scale_factor,
                                DecimalType<To>* dec_value) {
        if constexpr (check_overflow) {
            auto overflow =
                    DecimalV3Arithmetics<To, check_overflow>::mul(static_cast<To>(from_value), scale_factor, dec_value);
            if constexpr (sizeof(From) <= sizeof(To)) {
                return overflow;
            } else {
                static constexpr auto max_value = static_cast<From>(get_max<To>());
                static constexpr auto min_value = static_cast<From>(get_min<To>());
                return overflow || from_value > max_value || from_value < min_value;
            }
        } else {
            *dec_value = static_cast<To>(from_value * scale_factor);
            return false;
        }
    }

    template <typename From, typename To>
    static inline bool to_float(DecimalType<From> const& value, DecimalType<From> const& scale_factor,
                                FloatType<To>* to_value) {
        *to_value = static_cast<To>(static_cast<double>(value) / static_cast<double>(scale_factor));
        return false;
    }

    template <typename From, typename To, bool check_overflow>
    static inline bool scale_down(DecimalType<From> const& value, DecimalType<From> const& scale_factor,
                                  IntegerType<To>* to_value) {
        if constexpr (check_overflow) {
            From quotient;
            DecimalV3Arithmetics<From, check_overflow>::div(value, scale_factor, &quotient);
            *to_value = static_cast<To>(quotient);
            if constexpr (sizeof(From) <= sizeof(To)) {
                return false;
            } else {
                static constexpr auto max_value = static_cast<From>(get_max<To>());
                static constexpr auto min_value = static_cast<From>(get_min<To>());
                return quotient > max_value || quotient < min_value;
            }
        } else {
            *to_value = static_cast<To>(value / scale_factor);
            return false;
        }
    }

    template <typename From, DecimalRoundRule rule, bool check_overflow>
    static inline bool round_quotient(const DecimalType<From>& carry, DecimalType<From>& quotient,
                                      DecimalType<From> const& divisor, DecimalType<From> const& remainder) {
        bool need_round = false;

        if constexpr (rule == ROUND_HALF_UP || rule == ROUND_HALF_EVEN) {
            //TODO(by satanson): ROUND_HALF_UP is different from ROUND_HALF_EVEN
            need_round = abs(remainder) >= (divisor >> 1);
        } else if constexpr (rule == ROUND_FLOOR) {
            need_round = remainder > 0 && quotient > 0;
        } else if constexpr (rule == ROUND_CEILING) {
            need_round = remainder < 0 && quotient < 0;
        } else {
            // do nothing here
        }
        if (need_round) {
            return DecimalV3Arithmetics<From, check_overflow>::add(quotient, carry, &quotient);
        } else {
            return false;
        }
    }

    template <typename From, typename To, DecimalRoundRule rule, bool check_overflow>
    static inline bool scale_down_round(DecimalType<From> const& value, DecimalType<From> const& scale_factor,
                                        IntegerType<To>* to_value) {
        if (UNLIKELY(scale_factor == 1)) {
            return to_decimal_trivial<From, To, check_overflow>(value, to_value);
        }

        From quotient, remainder;
        bool overflow;

        // if value < 0, then carry = -1; otherwise, carry = 1.
        From carry = (value >> (sizeof(From) * 8 - 1)) | 1;

        if constexpr (check_overflow) {
            DecimalV3Arithmetics<From, check_overflow>::div(value, scale_factor, &quotient);
            DecimalV3Arithmetics<From, check_overflow>::mod(value, scale_factor, &remainder);
            overflow = round_quotient<From, rule, true>(carry, quotient, scale_factor, remainder);
            *to_value = static_cast<To>(quotient);
            if constexpr (sizeof(From) > sizeof(To)) {
                static constexpr auto max_value = static_cast<From>(get_max<To>());
                static constexpr auto min_value = static_cast<From>(get_min<To>());
                return overflow || quotient > max_value || quotient < min_value;
            } else {
                return overflow;
            }
        } else {
            quotient = value / scale_factor;
            remainder = value % scale_factor;
            round_quotient<From, rule, false>(carry, quotient, scale_factor, remainder);
            *to_value = static_cast<To>(quotient);
            return false;
        }
    }

    template <typename From, typename To, bool check_overflow>
    static inline bool from_integer(From from_value, To const& scale_factor, To* dec_value) {
        return scale_up<From, To, check_overflow>(from_value, scale_factor, dec_value);
    }

    template <typename From, typename To, bool check_overflow>
    static inline bool to_integer(From const& dec_value, From const& scale_factor, To* to_value) {
        return scale_down<From, To, check_overflow>(dec_value, scale_factor, to_value);
    }

    template <typename From, typename To, typename S, bool is_scale_up, bool check_overflow>
    static inline bool to_decimal_truncate(From const& value, S const& scale_factor, To* to_value) {
        if constexpr (is_scale_up) {
            static_assert(std::is_same_v<S, To>, "S and U must be the same type");
            return scale_up<From, To, check_overflow>(value, scale_factor, to_value);
        } else {
            static_assert(std::is_same_v<From, S>, "T and S must be the same type");
            return scale_down<From, To, check_overflow>(value, scale_factor, to_value);
        }
    }

    template <typename From, typename To, typename S, bool is_scale_up, bool check_overflow>
    static inline bool to_decimal(From const& value, S const& scale_factor, To* to_value) {
        return round<From, To, S, ROUND_HALF_EVEN, is_scale_up, check_overflow>(value, scale_factor, to_value);
    }

    template <typename From, typename To, bool check_overflow>
    static inline bool to_decimal_trivial(From const& value, To* to_value) {
        *to_value = static_cast<To>(value);
        if constexpr (check_overflow) {
            if constexpr (sizeof(From) <= sizeof(To)) {
                return false;
            } else {
                static constexpr auto max_value = static_cast<From>(get_max<To>());
                static constexpr auto min_value = static_cast<From>(get_min<To>());
                return value > max_value || value < min_value;
            }
        } else {
            return false;
        }
    }

    template <typename From, typename To, typename S, DecimalRoundRule rule, bool is_scale_up, bool check_overflow>
    static inline bool round(From const& value, S const& scale_factor, To* to_value) {
        if constexpr (is_scale_up) {
            static_assert(std::is_same_v<S, To>, "S and U must be the same type");
            return scale_up<From, To, check_overflow>(value, scale_factor, to_value);
        } else {
            static_assert(std::is_same_v<From, S>, "T and S must be the same type");
            return scale_down_round<From, To, rule, check_overflow>(value, scale_factor, to_value);
        }
    }

    template <typename T, DecimalRoundRule rule, bool is_scale_up, bool check_overflow>
    static inline bool round(T const& value, T const& scale_factor, T* to_value) {
        return round<T, T, T, rule, is_scale_up, check_overflow>(value, scale_factor, to_value);
    }
};

} // namespace starrocks
