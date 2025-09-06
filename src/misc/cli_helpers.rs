use clap::{Command, ArgAction};
use serde_json::json;

pub fn describe_cli_api(cmd: &Command) -> serde_json::Value {
    json!({
        "name": cmd.get_name(),
        "about": cmd.get_about().map(|s| s.to_string()),
        "version": cmd.get_version(),
        "args": cmd.get_arguments().map(|arg| {
            json!({
                "name": arg.get_id().as_str(),
                "long": arg.get_long(),
                "short": arg.get_short().map(|c| c.to_string()),
                "help": arg.get_help().map(|s| s.to_string()),
                "required": arg.is_required_set(),
                "multiple": matches!(arg.get_action(), ArgAction::Count | ArgAction::Append),
                "possible_values": arg.get_possible_values().iter()
                    .map(|pv| pv.get_name()).collect::<Vec<_>>()
            })
        }).collect::<Vec<_>>(),
        "subcommands": cmd.get_subcommands().map(|sub| describe_cli_api(sub)).collect::<Vec<_>>()
    })
}
