// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.connector.paimon;

import com.starrocks.catalog.ArrayType;
import com.starrocks.catalog.MapType;
import com.starrocks.catalog.PrimitiveType;
import com.starrocks.catalog.ScalarType;
import com.starrocks.catalog.StructField;
import com.starrocks.catalog.StructType;
import com.starrocks.catalog.Type;
import com.starrocks.sql.analyzer.SemanticException;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.DataTypes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Converts StarRocks DDL types to the subset of Paimon types supported by the Paimon connector.
 *
 * <p>Unlike the read-path converter, this converter is deliberately strict: accepting an
 * unsupported StarRocks type during DDL would create a table that cannot be read back faithfully.
 */
public final class PaimonTypeConverter {
    private static final int PAIMON_MAX_DECIMAL_PRECISION = 38;
    private static final int PAIMON_TIMESTAMP_PRECISION = 6;

    private PaimonTypeConverter() {
    }

    public static DataType toPaimonType(Type type) {
        return toPaimonType(type, new FieldIdGenerator());
    }

    private static DataType toPaimonType(Type type, FieldIdGenerator fieldIds) {
        if (type.isArrayType()) {
            return DataTypes.ARRAY(toPaimonType(((ArrayType) type).getItemType(), fieldIds).nullable());
        }
        if (type.isMapType()) {
            MapType mapType = (MapType) type;
            // Paimon map keys are never nullable.
            return DataTypes.MAP(toPaimonType(mapType.getKeyType(), fieldIds).notNull(),
                    toPaimonType(mapType.getValueType(), fieldIds).nullable());
        }
        if (type.isStructType()) {
            StructType structType = (StructType) type;
            List<DataField> fields = new ArrayList<>();
            Set<String> fieldNames = new HashSet<>();
            for (StructField field : structType.getFields()) {
                if (!fieldNames.add(field.getName().toLowerCase())) {
                    throw new SemanticException("Paimon struct contains duplicate field name: %s", field.getName());
                }
                fields.add(DataTypes.FIELD(fieldIds.next(), field.getName(),
                        toPaimonType(field.getType(), fieldIds).nullable()));
            }
            return DataTypes.ROW(fields.toArray(new DataField[0]));
        }
        if (!type.isScalarType()) {
            throw unsupported(type);
        }

        ScalarType scalarType = (ScalarType) type;
        PrimitiveType primitiveType = scalarType.getPrimitiveType();
        switch (primitiveType) {
            case BOOLEAN:
                return DataTypes.BOOLEAN();
            case TINYINT:
                return DataTypes.TINYINT();
            case SMALLINT:
                return DataTypes.SMALLINT();
            case INT:
                return DataTypes.INT();
            case BIGINT:
                return DataTypes.BIGINT();
            case FLOAT:
                return DataTypes.FLOAT();
            case DOUBLE:
                return DataTypes.DOUBLE();
            case DATE:
                return DataTypes.DATE();
            case TIME:
                return DataTypes.TIME();
            case DATETIME:
                return DataTypes.TIMESTAMP(PAIMON_TIMESTAMP_PRECISION);
            case CHAR:
                return DataTypes.CHAR(requirePositiveLength(scalarType, type));
            case VARCHAR:
                return DataTypes.VARCHAR(requirePositiveLength(scalarType, type));
            case VARBINARY:
                return DataTypes.BYTES();
            case DECIMALV2:
            case DECIMAL32:
            case DECIMAL64:
            case DECIMAL128:
            case DECIMAL256:
                return decimalType(scalarType, type);
            default:
                throw unsupported(type);
        }
    }

    private static DataType decimalType(ScalarType scalarType, Type type) {
        int precision = scalarType.getScalarPrecision();
        int scale = scalarType.getScalarScale();
        if (precision <= 0 || precision > PAIMON_MAX_DECIMAL_PRECISION || scale < 0 || scale > precision) {
            throw new SemanticException("Paimon supports DECIMAL precision between 1 and %s; invalid type: %s",
                    PAIMON_MAX_DECIMAL_PRECISION, type.toSql());
        }
        return DataTypes.DECIMAL(precision, scale);
    }

    private static int requirePositiveLength(ScalarType scalarType, Type type) {
        int length = scalarType.getLength();
        if (length <= 0) {
            throw new SemanticException("Paimon requires an explicit positive length for type: %s", type.toSql());
        }
        return length;
    }

    private static SemanticException unsupported(Type type) {
        return new SemanticException("Paimon does not support StarRocks type: %s", type.toSql());
    }

    private static class FieldIdGenerator {
        private int nextFieldId;

        private int next() {
            return nextFieldId++;
        }
    }
}
