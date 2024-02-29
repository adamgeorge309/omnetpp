import io
import subprocess
import numpy as np
import pandas as pd
from omnetpp.scave.utils import _pivot_results, _append_metadata_columns

"""
This module implements the same result querying API that is provided by the IDE to chart scripts,
using the opp_scavetool program to load the .sca and .vec files.
"""

inputfiles = list()

def set_inputs(input_patterns):
    global inputfiles
    inputfiles = list()
    add_inputs(input_patterns)

def add_inputs(input_patterns):
    global inputfiles
    if type(input_patterns) == str:
        input_patterns = [ input_patterns ]
    inputfiles = list(set(inputfiles + input_patterns))  # make unique

def _parse_int(s):
    # empty strings should become NaN, zeroes should stay zeroes, hence the type check
    return int(s) if (s or type(s) in [int, float]) else np.nan

def _parse_float(s):
    # empty strings should become NaN, zeroes should stay zeroes, hence the type check
    return float(s) if (s or type(s) in [int, float]) else np.nan

def _parse_ndarray(s):
    return np.fromstring(s, sep=' ') if s else None

_SCALAR_COLUMN_NAMES = ["value"]
_STATISTIC_COLUMN_NAMES = ["count", "sumweights", "mean", "stddev", "min", "max"]
_HISTOGRAM_COLUMN_NAMES = ["underflows", "overflows", "binedges", "binvalues"]
_VECTOR_COLUMN_NAMES = ["vectime", "vecvalue"]
_PARAMETER_COLUMN_NAMES = ["value"]

def _ensure_columns_exist(df, columns):
    for col in columns:
        if col not in df:
            df[col] = []

def get_serial():
    # return an (arbitrary) constant, as the set of loaded results doesn't change during a run of opp_charttool.
    return 1

def _read_csv(reader):
    df = pd.read_csv(reader, converters = {
        'count': _parse_int,
        'min': _parse_float,
        'max': _parse_float,
        'mean': _parse_float,
        'stddev': _parse_float,
        'sumweights': _parse_float,
        'underflows': _parse_float,
        'overflows': _parse_float,
        'binedges': _parse_ndarray,
        'binvalues': _parse_ndarray,
        'vectime': _parse_ndarray,
        'vecvalue': _parse_ndarray
    },
    low_memory=False) # This is here just to silence a DtypeWarning, should not have any other effect.

    if not df.empty:
        if "type" in df:
            # CSV-style results

            column_names = list(df.columns)
            def index_of(val):
                try:
                    return column_names.index(val)
                except ValueError:
                    return -1

            # Can't use name lookup in nones2arrays due to the `raw=True` parameter below,
            # basically for performance reasons.
            type_index = index_of("type")
            value_index = index_of("value")
            vectime_index = index_of("vectime")
            vecvalue_index = index_of("vecvalue")
            binedges_index = index_of("binedges")
            binvalues_index = index_of("binvalues")

            def nones2arrays(row):
                def none2array(i):
                    if i >= 0 and row[i] is None:
                        row[i] = np.array([])

                if row[type_index] == "scalar":
                    row[value_index] = _parse_float(row[value_index])
                elif row[type_index] == "vector":
                    none2array(vectime_index)
                    none2array(vecvalue_index)
                elif row[type_index] == "histogram":
                    none2array(binedges_index)
                    none2array(binvalues_index)

                return row

            # The `raw=True` parameter resulted in a significant (7x) performance improvement.
            # See https://stackoverflow.com/questions/19798153/difference-between-apply-and-applymap-for-a-pandas-dataframe
            df = df.apply(nones2arrays, axis=1, raw=True)

    df.rename(columns={"run": "runID"}, inplace=True) # oh, inconsistencies...

    # TODO: convert column dtype as well?
    return df


def _read_result_files(filter_expression, result_type, *additional_args):
    type_filter = ['-T', result_type] if result_type else []
    filter_expr_args = ['-f', filter_expression]
    command = ["opp_scavetool", "x", "--allow-nonmatching", *inputfiles, *type_filter, *filter_expr_args,
                "-F", "CSV-R", "-o", "-", *additional_args]

    proc = subprocess.run(command, stderr=subprocess.PIPE, stdout=subprocess.PIPE, universal_newlines=False)
    output_bytes = proc.stdout

    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.decode("utf-8").strip() + " (exit code " + str(proc.returncode) + ")")

    # TODO: stream the output through subprocess.PIPE ?
    return _read_csv(io.BytesIO(output_bytes))


def read_result_files(filenames, filter_expression, include_fields_as_scalars, vector_start_time, vector_end_time):
    if type(filenames) == str:
        filenames = [ filenames ]
    if filter_expression is not None and not filter_expression:
        raise ValueError("Empty filter expression")

    #type_filter = ['-T', result_type] if result_type else []
    type_filter = []

    args = []
    if include_fields_as_scalars:
        args.append("--add-fields-as-scalars")
    if vector_start_time is not None and not np.isnan(vector_start_time):
        args.append("--start-time")
        args.append(str(vector_start_time))
    if vector_end_time is not None and not np.isnan(vector_end_time):
        args.append("--end-time")
        args.append(str(vector_end_time))

    filter_expr_args = [] if filter_expression is None else ['-f', filter_expression]
    command = ["opp_scavetool", "x", "--allow-nonmatching", *filenames, *type_filter, *filter_expr_args,
                "-F", "CSV-R", "-o", "-", *args]

    proc = subprocess.run(command, stderr=subprocess.PIPE, stdout=subprocess.PIPE, universal_newlines=False)
    output_bytes = proc.stdout

    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.decode("utf-8").strip() + " (exit code " + str(proc.returncode) + ")")

    # TODO: stream the output through subprocess.PIPE ?
    return _read_csv(io.BytesIO(output_bytes))

def get_results(filter_expression, row_types, omit_unused_columns, include_fields_as_scalars, start_time, end_time):
    args = []
    if include_fields_as_scalars:
        args.append("--add-fields-as-scalars")
    if start_time is not None and not np.isnan(start_time):
        args.append("--start-time")
        args.append(str(start_time))
    if end_time is not None and not np.isnan(end_time):
        args.append("--end-time")
        args.append(str(end_time))
    df = _read_result_files(filter_expression, None, *args)

    if row_types is not None:
        df = df[df["type"].isin(row_types)]

    if omit_unused_columns:
        df.dropna(axis='columns', how='all', inplace=True)

    df.reset_index(inplace=True, drop=True)
    return df

def get_scalars(filter_expression, include_attrs, include_fields, include_runattrs, include_itervars, include_param_assignments, include_config_entries):
    args = []
    if include_fields:
        args.append("--add-fields-as-scalars")
    # TODO filter row types based on include_ args, as optimization
    df = _read_result_files(filter_expression, 's', *args)
    df = _pivot_results(df, include_attrs, include_runattrs, include_itervars, include_param_assignments, include_config_entries)
    _ensure_columns_exist(df, _SCALAR_COLUMN_NAMES)
    df["value"] = pd.to_numeric(df["value"], errors="raise")
    return df

def get_vectors(filter_expression, include_attrs, include_runattrs, include_itervars, include_param_assignments, include_config_entries, start_time, end_time):
    df = _read_result_files(filter_expression, 'v', '--start-time', str(start_time), '--end-time', str(end_time))
    df = _pivot_results(df, include_attrs, include_runattrs, include_itervars, include_param_assignments, include_config_entries)
    _ensure_columns_exist(df, _VECTOR_COLUMN_NAMES)
    return df

def get_statistics(filter_expression, include_attrs, include_runattrs, include_itervars, include_param_assignments, include_config_entries):
    df = _read_result_files(filter_expression, 't')
    df = _pivot_results(df, include_attrs, include_runattrs, include_itervars, include_param_assignments, include_config_entries)
    _ensure_columns_exist(df, _STATISTIC_COLUMN_NAMES)
    return df

def get_histograms(filter_expression, include_attrs, include_runattrs, include_itervars, include_param_assignments, include_config_entries):
    df = _read_result_files(filter_expression, 'h')
    df = _pivot_results(df, include_attrs, include_runattrs, include_itervars, include_param_assignments, include_config_entries)
    _ensure_columns_exist(df, _STATISTIC_COLUMN_NAMES + _HISTOGRAM_COLUMN_NAMES)
    return df

def get_parameters(filter_expression, include_attrs, include_runattrs, include_itervars, include_param_assignments, include_config_entries):
    df = _read_result_files(filter_expression, 'p')
    df = _pivot_results(df, include_attrs, include_runattrs, include_itervars, include_param_assignments, include_config_entries)
    _ensure_columns_exist(df, _PARAMETER_COLUMN_NAMES)
    return df


def _get_metadata(filter_expression, query_flag, include_runattrs, include_itervars, include_param_assignments, include_config_entries, columns=["runID", "name", "value"]):
    """
    Internal. See `opp_scavetool q -h`.
    - `query_flag`: Sets the type of metadata to query. One of: "-l", "-a", "-i", "-j", "-t"
    """
    command = ["opp_scavetool", "q", "--allow-nonmatching", *inputfiles, "-f", filter_expression, query_flag, "-g", "--tabs"]

    output = subprocess.check_output(command).strip()

    if len(output.decode("utf-8").splitlines()) == 0:
        print("<!> HINT: opp_scavetool returned an empty result. Consider adding a project name to directory mapping, for example: -p /aloha=../aloha")

    # TODO: stream the output through subprocess.PIPE ?
    df = pd.read_csv(io.BytesIO(output), sep='\t', header=None, names=columns, dtype="str")

    if include_itervars:
        iv = get_itervars("*", False, False, False, False)
        iv.rename(columns={"name": "attrname", "value": "attrvalue"}, inplace=True) # oh, inconsistencies...
        df = _append_metadata_columns(df, iv, "_itervar")

    if include_runattrs:
        ra = get_runattrs("*", False, False, False, False)
        ra.rename(columns={"name": "attrname", "value": "attrvalue"}, inplace=True) # oh, inconsistencies...
        df = _append_metadata_columns(df, ra, "_runattr")

    if include_config_entries:
        ce = get_config_entries("*", False, False, False, False)
        ce.rename(columns={"name": "attrname", "value": "attrvalue"}, inplace=True) # oh, inconsistencies...
        df = _append_metadata_columns(df, ce, "_config")

    if include_param_assignments and not include_config_entries:
        pa = get_param_assignments("*", False, False, False, False)
        pa.rename(columns={"name": "attrname", "value": "attrvalue"}, inplace=True) # oh, inconsistencies...
        df = _append_metadata_columns(df, pa, "_param")

    df.reset_index(inplace=True, drop=True)
    return df


def get_runs(filter_expression, include_runattrs, include_itervars, include_param_assignments, include_config_entries):
    return _get_metadata(filter_expression, "-r", include_runattrs, include_itervars, include_param_assignments, include_config_entries, columns=["runID"])

def get_runattrs(filter_expression, include_runattrs, include_itervars, include_param_assignments, include_config_entries):
    return _get_metadata(filter_expression, "-a", include_runattrs, include_itervars, include_param_assignments, include_config_entries)

def get_itervars(filter_expression, include_runattrs, include_itervars, include_param_assignments, include_config_entries):
    return _get_metadata(filter_expression, "-i", include_runattrs, include_itervars, include_param_assignments, include_config_entries)

def get_config_entries(filter_expression, include_runattrs, include_itervars, include_param_assignments, include_config_entries):
    return _get_metadata(filter_expression, "-j", include_runattrs, include_itervars, include_param_assignments, include_config_entries)

def get_param_assignments(filter_expression, include_runattrs, include_itervars, include_param_assignments, include_config_entries):
    return _get_metadata(filter_expression, "-t", include_runattrs, include_itervars, include_param_assignments, include_config_entries)
